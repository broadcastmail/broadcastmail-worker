package com.broadcastmail.worker.worker;

import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.common.exceptions.EmailSendException;
import com.broadcastmail.worker.common.exceptions.RecipientNotFoundException;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class OutboxProcessor {
    private final EmailSendService emailSendService;
    private final OutboxEntryRepository outboxEntryRepository;
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final CampaignCompletionService campaignCompletionService;

    public void process(OutboxEntry outboxEntry) {
        CampaignRecipient recipient = campaignRecipientRepository
                .findById(outboxEntry.getCampaignRecipientId())
                .orElseThrow(() -> new RecipientNotFoundException(outboxEntry.getCampaignRecipientId()));


        try {
            SendResult result = emailSendService.sendEmail(recipient);
            outboxEntry.setStatus(OutboxStatus.DONE);
            outboxEntryRepository.save(outboxEntry);
            recipient.setStatus(RecipientStatus.SENT);
            recipient.setResendMessageId(result.messageId());
            recipient.setSentAt(OffsetDateTime.now(ZoneId.systemDefault()));
            campaignRecipientRepository.save(recipient);
            campaignCompletionService.checkAndComplete(recipient.getCampaignId());

        } catch (ResendRateLimitException e) {
            outboxEntry.setStatus(OutboxStatus.PENDING);
            outboxEntry.setNextAttemptAt(
                    LocalDate.now(ZoneId.systemDefault())
                            .plusDays(1)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toOffsetDateTime()
            );
            outboxEntryRepository.save(outboxEntry);

        } catch (EmailSendException e) {
            outboxEntry.setAttempts(outboxEntry.getAttempts() + 1);
            outboxEntry.setLastAttemptedAt(OffsetDateTime.now(ZoneId.systemDefault()));

            if (outboxEntry.getAttempts() < 3) {
                outboxEntry.setStatus(OutboxStatus.PENDING);
                int backoffMinutes = switch (outboxEntry.getAttempts()) {
                    case 1 -> 1;
                    case 2 -> 5;
                    default -> 15;
                };
                outboxEntry.setNextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()).plusMinutes(backoffMinutes));
            } else {
                outboxEntry.setStatus(OutboxStatus.FAILED);
                recipient.setStatus(RecipientStatus.FAILED);
                campaignCompletionService.checkAndComplete(recipient.getCampaignId());
                recipient.setFailedReason(e.getMessage());
                campaignRecipientRepository.save(recipient);
            }
            outboxEntryRepository.save(outboxEntry);
        }
    }
}