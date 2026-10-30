package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
import com.broadcastmail.common.suppressions.Suppression;
import com.broadcastmail.common.suppressions.SuppressionsRepository;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.worker.common.exceptions.EmailProviderNotFoundException;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.resend.ResendClient;
import com.broadcastmail.worker.resend.ResendUsageService;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.broadcastmail.worker.unsubscribe.UnsubscribeTokenService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.resend.services.batch.model.BatchEmail;
import com.resend.services.batch.model.BatchError;
import com.resend.services.batch.model.CreateBatchEmailsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmailSendService {

    private static final Pattern TEMPLATE_VAR_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_]\\w*)\\}\\}");

    private final CampaignRepository campaignRepository;
    private final EmailProviderRepository emailProviderRepository;
    private final ResendClient resendClient;
    private final ResendUsageService resendUsageService;
    private final EncryptionProperties encryptionProperties;
    private final UnsubscribeTokenService unsubscribeTokenService;
    private final SuppressionsRepository suppressionsRepository;

    private final Cache<UUID, Campaign> campaignCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();
    private final Cache<UUID, EmailProvider> emailProviderCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public List<BatchSendOutcome> sendEmailBatch(
            List<CampaignRecipient> recipients,
            Map<String, Map<String, Object>> campaignAttrs) {

        if (recipients.isEmpty()) return List.of();

        UUID campaignId = recipients.getFirst().getCampaignId();
        Campaign campaign = campaignCache.get(campaignId, id ->
                campaignRepository.findById(id)
                        .orElseThrow(() -> new CampaignNotFoundException(id)));

        EmailProvider emailProvider = emailProviderCache.get(campaign.getAccountId(), accountId ->
                emailProviderRepository.findByAccountId(accountId)
                        .orElseThrow(() -> new EmailProviderNotFoundException(accountId)));

        String apiKey = SecurityUtil.decrypt(emailProvider.getEncryptedApiKey(), encryptionProperties.key());

        List<String> emails = recipients.stream().map(CampaignRecipient::getEmail).toList();
        Set<String> suppressed = suppressionsRepository
                .findByAccountIdAndEmailIn(campaign.getAccountId(), emails)
                .stream()
                .map(Suppression::getEmail)
                .collect(Collectors.toSet());
        List<CampaignRecipient> toSend = recipients.stream()
                .filter(r -> !suppressed.contains(r.getEmail()))
                .toList();

        List<BatchSendOutcome> outcomes = new ArrayList<>(Collections.nCopies(recipients.size(), null));
        for (int i = 0; i < recipients.size(); i++) {
            if (suppressed.contains(recipients.get(i).getEmail())) {
                outcomes.set(i, new BatchSendOutcome(recipients.get(i), null, "SUPPRESSED"));
            }
        }

        if (toSend.isEmpty()) return outcomes;

        List<ResendSendRequest> requests = buildRequests(toSend, campaign, emailProvider, campaignAttrs);
        CreateBatchEmailsResponse response = resendClient.sendEmailBatch(apiKey, requests);
        fillSendOutcomes(outcomes, toSend, response);
        return outcomes;
    }

    private List<ResendSendRequest> buildRequests(List<CampaignRecipient> toSend, Campaign campaign,
                                                   EmailProvider emailProvider,
                                                   Map<String, Map<String, Object>> campaignAttrs) {
        List<ResendSendRequest> requests = new ArrayList<>();
        for (CampaignRecipient recipient : toSend) {
            String extId = recipient.getExternalUserId();
            Map<String, Object> attrs = extId != null ? campaignAttrs.getOrDefault(extId, Map.of()) : Map.of();
            String bodyHtml = applyTemplateVariables(campaign.getBodyHtml(), attrs);
            String unsubscribeUrl = unsubscribeTokenService.generateUnsubscribeUrl(recipient.getId());
            String bodyWithUnsubscribe = bodyHtml + "<br><br><a href=\"" + unsubscribeUrl + "\">Unsubscribe</a>";
            requests.add(new ResendSendRequest(
                    emailProvider.getFromAddress(),
                    List.of(recipient.getEmail()),
                    campaign.getSubject(),
                    bodyWithUnsubscribe,
                    unsubscribeUrl,
                    recipient.getIdempotencyKey()
            ));
        }
        return requests;
    }

    private void fillSendOutcomes(List<BatchSendOutcome> outcomes, List<CampaignRecipient> toSend,
                                   CreateBatchEmailsResponse response) {
        Map<Integer, String> failedIndexes = new HashMap<>();
        if (response.getErrors() != null) {
            for (BatchError error : response.getErrors()) {
                failedIndexes.put(error.getIndex(), error.getMessage());
            }
        }

        List<BatchEmail> data = response.getData() != null ? response.getData() : List.of();
        int successIndex = 0;
        int sendIdx = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) != null) continue;
            CampaignRecipient recipient = toSend.get(sendIdx++);
            if (failedIndexes.containsKey(sendIdx - 1)) {
                outcomes.set(i, new BatchSendOutcome(recipient, null, failedIndexes.get(sendIdx - 1)));
            } else {
                String messageId = successIndex < data.size() ? data.get(successIndex++).getId() : null;
                outcomes.set(i, new BatchSendOutcome(recipient, messageId, null));
            }
        }
    }

    public OptionalInt getRemainingDailyQuota(UUID campaignId) {
        Campaign campaign = campaignCache.get(campaignId, id ->
                campaignRepository.findById(id)
                        .orElseThrow(() -> new CampaignNotFoundException(id)));
        EmailProvider emailProvider = emailProviderCache.get(campaign.getAccountId(), accountId ->
                emailProviderRepository.findByAccountId(accountId)
                        .orElseThrow(() -> new EmailProviderNotFoundException(accountId)));
        String apiKey = SecurityUtil.decrypt(emailProvider.getEncryptedApiKey(), encryptionProperties.key());
        return resendUsageService.getRemainingDailyQuota(apiKey);
    }

    public void evictDailyQuota(UUID campaignId) {
        Campaign campaign = campaignCache.getIfPresent(campaignId);
        if (campaign == null) return;
        EmailProvider emailProvider = emailProviderCache.getIfPresent(campaign.getAccountId());
        if (emailProvider == null) return;
        String apiKey = SecurityUtil.decrypt(emailProvider.getEncryptedApiKey(), encryptionProperties.key());
        resendUsageService.evict(apiKey);
    }

    void evictCampaign(UUID campaignId) {
        campaignCache.invalidate(campaignId);
    }

    private static String applyTemplateVariables(String html, Map<String, Object> attrs) {
        if (!html.contains("{{")) return html;
        StringBuffer sb = new StringBuffer();
        Matcher m = TEMPLATE_VAR_PATTERN.matcher(html);
        while (m.find()) {
            Object val = attrs.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val.toString() : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
