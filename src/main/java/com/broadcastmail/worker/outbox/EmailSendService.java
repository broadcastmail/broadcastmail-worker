package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.worker.common.exceptions.EmailProviderNotFoundException;
import com.broadcastmail.worker.resend.ResendClient;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.broadcastmail.worker.unsubscribe.UnsubscribeTokenService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    private final EncryptionProperties encryptionProperties;
    private final UnsubscribeTokenService unsubscribeTokenService;

    private final Cache<UUID, Campaign> campaignCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();
    private final Cache<UUID, EmailProvider> emailProviderCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public SendResult sendEmail(CampaignRecipient recipient, Map<String, Object> attrs) {

        Campaign campaign = campaignCache.get(recipient.getCampaignId(), id ->
                campaignRepository.findById(id)
                        .orElseThrow(() -> new CampaignNotFoundException(id)));

        EmailProvider emailProvider = emailProviderCache.get(campaign.getAccountId(), accountId ->
                emailProviderRepository.findByAccountId(accountId)
                        .orElseThrow(() -> new EmailProviderNotFoundException(accountId)));

        String apiKey = SecurityUtil.decrypt(emailProvider.getEncryptedApiKey(), encryptionProperties.key());

        String bodyHtml = applyTemplateVariables(campaign.getBodyHtml(), attrs);
        String unsubscribeUrl = unsubscribeTokenService.generateUnsubscribeUrl(recipient.getId());
        String bodyWithUnsubscribe = bodyHtml +
                "<br><br><a href=\"" + unsubscribeUrl + "\">Unsubscribe</a>";

        ResendSendRequest request = new ResendSendRequest(
                emailProvider.getFromAddress(),
                List.of(recipient.getEmail()),
                campaign.getSubject(),
                bodyWithUnsubscribe,
                unsubscribeUrl
        );

        String messageId = resendClient.sendEmail(apiKey, request, recipient.getIdempotencyKey());
        return new SendResult(messageId, recipient);
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
