package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BatchPersistenceService {

    private final CampaignRecipientRepository campaignRecipientRepository;
    private final OutboxEntryRepository outboxEntryRepository;

    @Transactional
    public void persistBatch(UUID campaignId, List<RecipientRow> batch) {
        if(batch.isEmpty()) {
            return;
        }
        String[] userIds = batch.stream().map(RecipientRow::userId).toArray(String[]::new);
        String[] emails = batch.stream().map(RecipientRow::email).toArray(String[]::new);

        OffsetDateTime insertedAfter = OffsetDateTime.now(ZoneId.systemDefault());
        campaignRecipientRepository.upsertRecipients(campaignId, userIds, emails);
        List<UUID> insertedIds = campaignRecipientRepository
                .findInsertedAfter(campaignId, Arrays.asList(userIds), insertedAfter);

        if (insertedIds.isEmpty()) return;

        List<OutboxEntry> outboxEntries = insertedIds.stream()
                .map(id -> OutboxEntry.builder()
                        .campaignRecipientId(id)
                        .status(OutboxStatus.PENDING)
                        .attempts(0)
                        .nextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()))
                        .build())
                .toList();

        outboxEntryRepository.saveAll(outboxEntries);
    }
}