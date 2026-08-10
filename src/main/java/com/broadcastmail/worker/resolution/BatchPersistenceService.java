package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BatchPersistenceService {

    private final CampaignRecipientRepository campaignRecipientRepository;
    private final OutboxEntryRepository outboxEntryRepository;

    @Transactional
    public void persistBatch(UUID campaignId, List<RecipientRow> batch) {
        List<CampaignRecipient> recipients = batch.stream()
                .map(r -> CampaignRecipient.builder()
                        .campaignId(campaignId)
                        .externalUserId(r.userId())
                        .email(r.email())
                        .status(RecipientStatus.QUEUED)
                        .idempotencyKey(campaignId + ":" + r.userId())
                        .build())
                .toList();

        campaignRecipientRepository.saveAll(recipients);

        List<OutboxEntry> outboxEntries = recipients.stream()
                .map(r -> OutboxEntry.builder()
                        .campaignRecipientId(r.getId())
                        .status(OutboxStatus.PENDING)
                        .attempts(0)
                        .nextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()))
                        .build())
                .toList();

        outboxEntryRepository.saveAll(outboxEntries);
    }
}