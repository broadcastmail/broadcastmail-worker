package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchPersistenceServiceTest {

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID RECIPIENT_ID_1 = UUID.randomUUID();

    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private OutboxEntryRepository outboxEntryRepository;

    @InjectMocks private BatchPersistenceService batchPersistenceService;

    @Test
    void shouldSkipOutboxWhenAllRecipientsAlreadyExist() {
        // Given
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(2);
        when(campaignRecipientRepository.findInsertedAfter(eq(CAMPAIGN_ID), anyList(), any()))
                .thenReturn(List.of());

        // When
        batchPersistenceService.persistBatch(CAMPAIGN_ID, batch);

        // Then
        verify(outboxEntryRepository, never()).saveAll(any());
    }

    @Test
    void shouldCreateOutboxEntriesOnlyForNewlyInsertedRows() {
        // Given — 2 recipients in batch, only 1 actually inserted (other already existed)
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(2);
        when(campaignRecipientRepository.findInsertedAfter(eq(CAMPAIGN_ID), any(), any()))
                .thenReturn(List.of(RECIPIENT_ID_1));

        // When
        batchPersistenceService.persistBatch(CAMPAIGN_ID, batch);

        // Then — only 1 outbox entry, not 2
        verify(outboxEntryRepository).saveAll(argThat((List<OutboxEntry> list) -> list.size() == 1));    }

    @Test
    void shouldNotCallUpsertWhenBatchIsEmpty() {
        // When
        batchPersistenceService.persistBatch(CAMPAIGN_ID, List.of());

        // Then
        verify(campaignRecipientRepository, never()).upsertRecipients(any(), any(), any());
        verify(outboxEntryRepository, never()).saveAll(any());
    }
}