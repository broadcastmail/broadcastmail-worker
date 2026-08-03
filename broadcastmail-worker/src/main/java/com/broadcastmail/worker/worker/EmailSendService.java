package com.broadcastmail.worker.worker;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.config.EncryptionProperties;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.worker.common.exceptions.EmailProviderNotFoundException;
import com.broadcastmail.worker.resend.ResendClient;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmailSendService {


    private final CampaignRepository campaignRepository;
    private final EmailProviderRepository emailProviderRepository;
    private final ResendClient resendClient;
    private final EncryptionProperties encryptionProperties;

    public SendResult sendEmail(CampaignRecipient recipient) {

        Campaign campaign = campaignRepository.findById(recipient.getCampaignId())
                .orElseThrow(() -> new CampaignNotFoundException( recipient.getCampaignId()));

        EmailProvider emailProvider = emailProviderRepository.findByAccountId(campaign.getAccountId())
                .orElseThrow(() -> new EmailProviderNotFoundException(campaign.getAccountId()));

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
