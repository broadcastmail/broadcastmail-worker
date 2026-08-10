package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.filter.CampaignFilterRepository;
import com.broadcastmail.common.campaign.filter.CampaignFilterSerializer;
import com.broadcastmail.common.campaign.filter.FilterQuery;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.worker.account.AccountPlanService;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.PlanLimitExceededException;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResolutionServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @Mock private AccountPlanService accountPlanService;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private CampaignFilterRepository filterRepository;
    @Mock private CampaignFilterSerializer filterSerializer;
    @Mock private BatchPersistenceService batchPersistenceService;
    @Mock private ExternalRecipientQueryService externalRecipientQueryService;
    @Mock private CampaignRepository campaignRepository;
    @Mock private EncryptionProperties encryptionProperties;

    @InjectMocks private ResolutionService resolutionService;

    private Campaign resolvingCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .accountId(ACCOUNT_ID)
                .status(CampaignStatus.RESOLVING)
                .build();
    }

    private Connection connection() {
        return Connection.builder()
                .encryptedCreds(SecurityUtil.encrypt("testpassword", CampaignTestFixtures.TEST_ENCRYPTION_KEY))
                .projectRef("test-ref")
                .userIdColumn("id")
                .emailColumn("email")
                .userTableSchema("public")
                .userTableName("profiles")
                .build();
    }

    @Test
    void shouldTransitionToSendingWhenResolutionSucceeds() {
        // Given
        Campaign campaign = resolvingCampaign();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(0), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(2));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(100), anyInt()))
                .thenReturn(List.of());

        // When
        resolutionService.resolve(campaign);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.SENDING);
        assertThat(campaign.getRecipientCount()).isEqualTo(2);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldPersistEachBatchSeparately() {
        // Given
        Campaign campaign = resolvingCampaign();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(0), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(100));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(100), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(50));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(200), anyInt()))
                .thenReturn(List.of());

        // When
        resolutionService.resolve(campaign);

        // Then
        verify(batchPersistenceService, times(2)).persistBatch(eq(CAMPAIGN_ID), any());
        assertThat(campaign.getRecipientCount()).isEqualTo(150);
    }

    @Test
    void shouldFailCampaignWhenPlanLimitExceeded() {
        // Given
        Campaign campaign = resolvingCampaign();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(0), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(2));
        doThrow(new PlanLimitExceededException()).when(accountPlanService).checkRecipientLimit(ACCOUNT_ID);

        // When
        resolutionService.resolve(campaign);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.FAILED);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldFailCampaignWhenConnectionNotFound() {
        // Given
        Campaign campaign = resolvingCampaign();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        // When
        resolutionService.resolve(campaign);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.FAILED);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldFailCampaignWhenExternalQueryFails() {
        // Given
        Campaign campaign = resolvingCampaign();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Supabase unreachable"));

        // When
        resolutionService.resolve(campaign);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.FAILED);
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldCheckPlanLimitAfterEachBatch() {
        // Given
        Campaign campaign = resolvingCampaign();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(0), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(100));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(100), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(50));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(200), anyInt()))
                .thenReturn(List.of());

        // When
        resolutionService.resolve(campaign);

        // Then
        verify(accountPlanService, times(2)).checkRecipientLimit(ACCOUNT_ID);
    }
}