package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.account.Account;
import com.broadcastmail.common.usage.RecipientUsageRepository;
import com.broadcastmail.common.account.AccountRepository;
import com.broadcastmail.common.account.plan.Plan;
import com.broadcastmail.common.campaign.AudienceMode;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.filter.CampaignFilterRepository;
import com.broadcastmail.common.campaign.filter.CampaignFilterSerializer;
import com.broadcastmail.common.campaign.filter.FilterQuery;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.SupabaseConnectionException;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import com.broadcastmail.worker.outbox.CampaignStatusNotifier;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResolutionServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @Mock private ConnectionRepository connectionRepository;
    @Mock private CampaignFilterRepository filterRepository;
    @Mock private CampaignFilterSerializer filterSerializer;
    @Mock private BatchPersistenceService batchPersistenceService;
    @Mock private ExternalRecipientQueryService externalRecipientQueryService;
    @Mock private CampaignRepository campaignRepository;
    @Mock private EncryptionProperties encryptionProperties;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private RecipientUsageRepository recipientUsageRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CampaignStatusNotifier statusNotifier;
    @InjectMocks private ResolutionService resolutionService;
    private Campaign resolvingCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .accountId(ACCOUNT_ID)
                .status(CampaignStatus.RESOLVING)
                .build();
    }

    private Account account() {
        return Account.builder()
                .id(ACCOUNT_ID)
                .plan(Plan.FREE)
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
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any(), anyString(), any())).thenReturn(new FilterQuery("", List.of()));
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
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any(), anyString(), any())).thenReturn(new FilterQuery("", List.of()));
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
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any(), anyString(), any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(0), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(2));
        when(recipientUsageRepository.findEmailsSince(eq(ACCOUNT_ID), any()))
                .thenReturn(IntStream.rangeClosed(1, 499).mapToObj(i -> "earlier" + i + "@example.com").toList());
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
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new SupabaseConnectionException("Supabase unreachable", null));

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
        Account account = Account.builder()
                .id(ACCOUNT_ID)
                .plan(Plan.FREE)
                .build();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any(), anyString(), any())).thenReturn(new FilterQuery("", List.of()));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(0), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(100));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(100), anyInt()))
                .thenReturn(CampaignTestFixtures.recipientRows(50));
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(200), anyInt()))
                .thenReturn(List.of());

        // When
        resolutionService.resolve(campaign);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.SENDING);
        assertThat(campaign.getRecipientCount()).isEqualTo(150);
    }

    @Test
    void shouldApplySavedFiltersAndSkipUntickedRowsWhenEveryoneIsSelected() {
        // Given
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).accountId(ACCOUNT_ID).status(CampaignStatus.RESOLVING)
                .audienceMode(AudienceMode.ALL).excludedIds(List.of("2")).build();
        FilterQuery filterQuery = new FilterQuery("WHERE linked.\"plan\" = ?", List.of("pro"));
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any(), anyString(), any())).thenReturn(filterQuery);
        when(externalRecipientQueryService.resolve(any(), any(), eq(filterQuery), eq(0), anyInt()))
                .thenReturn(List.of(new RecipientRow("1", "a@example.com"), new RecipientRow("2", "b@example.com")));
        when(externalRecipientQueryService.resolve(any(), any(), eq(filterQuery), eq(100), anyInt()))
                .thenReturn(List.of());

        // When
        resolutionService.resolve(campaign);

        // Then
        verify(batchPersistenceService).persistBatch(CAMPAIGN_ID, List.of(new RecipientRow("1", "a@example.com")));
        assertThat(campaign.getRecipientCount()).isEqualTo(1);
    }

    @Test
    void shouldSkipPastRecipientsOfTheExcludedCampaignWhenPickedManually() {
        // Given
        UUID excludedCampaignId = UUID.randomUUID();
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).accountId(ACCOUNT_ID).status(CampaignStatus.RESOLVING)
                .audienceMode(AudienceMode.MANUAL).includedIds(List.of("1", "2")).excludedCampaignId(excludedCampaignId).build();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(campaignRecipientRepository.findDistinctEmailsByCampaignId(excludedCampaignId)).thenReturn(List.of("b@example.com"));
        when(externalRecipientQueryService.resolveByIds(any(), any(), eq(List.of("1", "2"))))
                .thenReturn(List.of(new RecipientRow("1", "a@example.com"), new RecipientRow("2", "b@example.com")));

        // When
        resolutionService.resolve(campaign);

        // Then
        verify(batchPersistenceService).persistBatch(CAMPAIGN_ID, List.of(new RecipientRow("1", "a@example.com")));
        verifyNoInteractions(filterSerializer);
    }

    @Test
    void shouldNotCountAddressesAlreadyEmailedThisPeriodAgainstThePlanLimit() {
        // Given
        Campaign campaign = resolvingCampaign();
        List<RecipientRow> rows = CampaignTestFixtures.recipientRows(2);
        List<String> earlier = IntStream.rangeClosed(1, 499).mapToObj(i -> "user" + i + "@example.com").toList();
        when(connectionRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(connection()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(encryptionProperties.key()).thenReturn(CampaignTestFixtures.TEST_ENCRYPTION_KEY);
        when(filterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
        when(filterSerializer.serialize(any(), anyString(), any())).thenReturn(new FilterQuery("", List.of()));
        when(recipientUsageRepository.findEmailsSince(eq(ACCOUNT_ID), any())).thenReturn(earlier);
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(0), anyInt())).thenReturn(rows);
        when(externalRecipientQueryService.resolve(any(), any(), any(), eq(100), anyInt())).thenReturn(List.of());

        // When
        resolutionService.resolve(campaign);

        // Then
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.SENDING);
        assertThat(campaign.getRecipientCount()).isEqualTo(2);
    }
}
