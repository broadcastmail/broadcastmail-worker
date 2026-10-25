package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        List<ResendSendRequest> requests = new ArrayList<>();
        for (CampaignRecipient recipient : recipients) {
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

        CreateBatchEmailsResponse response = resendClient.sendEmailBatch(apiKey, requests);

        Map<Integer, String> failedIndexes = new HashMap<>();
        if (response.getErrors() != null) {
            for (BatchError error : response.getErrors()) {
                failedIndexes.put(error.getIndex(), error.getMessage());
            }
        }

        List<BatchEmail> data = response.getData() != null ? response.getData() : List.of();
        List<BatchSendOutcome> outcomes = new ArrayList<>();
        int successIndex = 0;
        for (int i = 0; i < recipients.size(); i++) {
            CampaignRecipient recipient = recipients.get(i);
            if (failedIndexes.containsKey(i)) {
                outcomes.add(new BatchSendOutcome(recipient, null, failedIndexes.get(i)));
            } else {
                String messageId = successIndex < data.size() ? data.get(successIndex).getId() : null;
                outcomes.add(new BatchSendOutcome(recipient, messageId, null));
                successIndex++;
            }
        }
        return outcomes;
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
