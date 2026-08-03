package com.broadcastmail.worker.worker;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatusCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignCompletionServiceTest {
    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignRecipientRepository campaignRecipientRepository;

    @InjectMocks
    private CampaignCompletionService campaignCompletionService;

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        campaign = Campaign.builder()
                .id(CAMPAIGN_ID)
                .status(CampaignStatus.SENDING)
                .build();
    }

    private void stubCampaignFound() {
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(campaign));
    }

    private record CountsStub(long queued, long total, long failed) implements RecipientStatusCounts {
        public long getQueued() { return queued; }
        public long getTotal() { return total; }
        public long getFailed() { return failed; }
    }

    private void stubRecipientCounts(long queued, long total, long failed) {
        when(campaignRecipientRepository.countStatusesByCampaignId(CAMPAIGN_ID))
                .thenReturn(new CountsStub(queued, total, failed));
    }

    @Test
    void shouldMarkCampaignSentWhenAllRecipientsProcessed() {
        // Given
        stubCampaignFound();
        stubRecipientCounts(0L, 10L, 0L);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.SENT);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldMarkCampaignPartiallyFailedWhenMoreThan10PercentOfRecipientsFailed() {
        // Given
        stubCampaignFound();
        stubRecipientCounts(0L, 10L, 2L);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PARTIALLY_FAILED);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldMarkCampaignFailedWhenAllRecipientsFailed() {
        // Given
        stubCampaignFound();
        stubRecipientCounts(0L, 10L, 10L);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.FAILED);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldNotUpdateCampaignStatusWhileRecipientsStillQueued() {
        // Given
        stubRecipientCounts(5L, 0L, 0L);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        verify(campaignRepository, never()).save(any());
    }

}