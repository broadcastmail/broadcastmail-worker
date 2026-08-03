package com.broadcastmail.worker.worker;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
import com.broadcastmail.config.EncryptionProperties;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.worker.common.exceptions.EmailProviderNotFoundException;
import com.broadcastmail.worker.resend.ResendClient;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailSendService {


    private final CampaignRepository campaignRepository;
    private final EmailProviderRepository emailProviderRepository;
    private final ResendClient resendClient;
    private final EncryptionProperties encryptionProperties;

    private final Cache<UUID, Campaign> campaignCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();
    private final Cache<UUID, EmailProvider> emailProviderCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public SendResult sendEmail(CampaignRecipient recipient) {

        Campaign campaign = campaignCache.get(recipient.getCampaignId(), id ->
                campaignRepository.findById(id)
                        .orElseThrow(() -> new CampaignNotFoundException(id)));

        EmailProvider emailProvider = emailProviderCache.get(campaign.getAccountId(), accountId ->
                emailProviderRepository.findByAccountId(accountId)
                        .orElseThrow(() -> new EmailProviderNotFoundException(accountId)));

        String apiKey = SecurityUtil.decrypt(emailProvider.getEncryptedApiKey(), encryptionProperties.key());

        ResendSendRequest request = new ResendSendRequest(
                emailProvider.getFromAddress(),
                List.of(recipient.getEmail()),
                campaign.getSubject(),
                campaign.getBodyHtml()
        );

        String messageId = resendClient.sendEmail(apiKey, request, recipient.getIdempotencyKey());
        return new SendResult(messageId,recipient);

    }
}
