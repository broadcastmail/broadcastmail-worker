package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
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

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BatchPersistenceServiceTest {

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private OutboxEntryRepository outboxEntryRepository;

    @InjectMocks private BatchPersistenceService batchPersistenceService;

    @Test
    void shouldPersistOneRecipientRowPerResolvedUser() {
        // Given
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(2);

        // When
        batchPersistenceService.persistBatch(CAMPAIGN_ID, batch);

        // Then
        verify(campaignRecipientRepository).saveAll(argThat(list -> ((List<?>) list).size() == 2));
    }

    @Test
    void shouldPersistOneOutboxRowPerRecipient() {
        // Given
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(2);

        // When
        batchPersistenceService.persistBatch(CAMPAIGN_ID, batch);

        // Then
        verify(outboxEntryRepository).saveAll(argThat(list -> ((List<?>) list).size() == 2));
    }
}