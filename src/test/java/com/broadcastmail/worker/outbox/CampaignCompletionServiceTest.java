package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatusCounts;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignCompletionServiceTest {

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;

    @InjectMocks private CampaignCompletionService campaignCompletionService;

    private Campaign sendingCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .status(CampaignStatus.SENDING)
                .build();
    }

    @Test
    void shouldDoNothingWhenRecipientsStillQueued() {
        // Given
        when(campaignRecipientRepository.countStatusesByCampaignId(CAMPAIGN_ID))
                .thenReturn(counts(1, 0, 5));

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        verifyNoInteractions(campaignRepository);
    }

    @Test
    void shouldMarkCampaignAsSentWhenAllDelivered() {
        // Given
        when(campaignRecipientRepository.countStatusesByCampaignId(CAMPAIGN_ID))
                .thenReturn(counts(0, 0, 5));
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(sendingCampaign()));

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        Campaign saved = capturesSaved();
        assertThat(saved.getStatus()).isEqualTo(CampaignStatus.SENT);
    }

    @Test
    void shouldMarkCampaignAsFailedWhenAllFailed() {
        // Given
        when(campaignRecipientRepository.countStatusesByCampaignId(CAMPAIGN_ID))
                .thenReturn(counts(0, 5, 5));
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(sendingCampaign()));
        when(campaignRecipientRepository.deleteFailedBatch(CAMPAIGN_ID, 100))
                .thenReturn(0);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        Campaign saved = capturesSaved();
        assertThat(saved.getStatus()).isEqualTo(CampaignStatus.FAILED);
    }

    @Test
    void shouldMarkCampaignAsPartiallyFailedWhenMoreThan10PercentFailed() {
        // Given
        when(campaignRecipientRepository.countStatusesByCampaignId(CAMPAIGN_ID))
                .thenReturn(counts(0, 2, 10));
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(sendingCampaign()));
        when(campaignRecipientRepository.deleteFailedBatch(CAMPAIGN_ID, 100))
                .thenReturn(0);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        Campaign saved = capturesSaved();
        assertThat(saved.getStatus()).isEqualTo(CampaignStatus.PARTIALLY_FAILED);
    }

    @Test
    void shouldBatchDeleteFailedRecipientsWhenCampaignFails() {
        // Given
        when(campaignRecipientRepository.countStatusesByCampaignId(CAMPAIGN_ID))
                .thenReturn(counts(0, 5, 5));
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(sendingCampaign()));
        when(campaignRecipientRepository.deleteFailedBatch(CAMPAIGN_ID, 100))
                .thenReturn(5)
                .thenReturn(0);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        verify(campaignRecipientRepository, times(2)).deleteFailedBatch(CAMPAIGN_ID, 100);
    }

    @Test
    void shouldThrowWhenCampaignNotFound() {
        // Given
        when(campaignRecipientRepository.countStatusesByCampaignId(CAMPAIGN_ID))
                .thenReturn(counts(0, 0, 5));
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> campaignCompletionService.checkAndComplete(CAMPAIGN_ID))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    private Campaign capturesSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        return captor.getValue();
    }

    private RecipientStatusCounts counts(long queued, long failed, long total) {
        return new RecipientStatusCounts() {
            @Override public long getQueued() { return queued; }
            @Override public long getTotal() { return total; }
            @Override public long getFailed() { return failed; }
        };
    }

}