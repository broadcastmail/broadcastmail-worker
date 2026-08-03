package com.broadcastmail.worker.worker;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    void shouldMarkCampaignSentWhenAllRecipientsProcessed() {
        // Given
        stubCampaignFound();
        when(campaignRecipientRepository
                .countByCampaignIdAndStatus(CAMPAIGN_ID, RecipientStatus.QUEUED))
                .thenReturn(0L);
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID))
                .thenReturn(10L);
        when(campaignRecipientRepository.countByCampaignIdAndStatus
                (CAMPAIGN_ID, RecipientStatus.FAILED)).thenReturn(0L);

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
        when(campaignRecipientRepository.countByCampaignIdAndStatus(CAMPAIGN_ID, RecipientStatus.QUEUED)).thenReturn(0L);
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(10L);
        when(campaignRecipientRepository.countByCampaignIdAndStatus(CAMPAIGN_ID, RecipientStatus.FAILED)).thenReturn(2L);

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
        when(campaignRecipientRepository.countByCampaignIdAndStatus(CAMPAIGN_ID, RecipientStatus.QUEUED)).thenReturn(0L);
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(10L);
        when(campaignRecipientRepository.countByCampaignIdAndStatus(CAMPAIGN_ID, RecipientStatus.FAILED)).thenReturn(10L);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.FAILED);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldNotUpdateCampaignStatusWhileRecipientsStillQueued() {
        // Given
        when(campaignRecipientRepository.countByCampaignIdAndStatus(CAMPAIGN_ID, RecipientStatus.QUEUED)).thenReturn(5L);

        // When
        campaignCompletionService.checkAndComplete(CAMPAIGN_ID);

        // Then
        verify(campaignRepository, never()).save(any());
    }

}