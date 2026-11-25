package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.account.Account;
import com.broadcastmail.common.account.AccountRepository;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.suppressions.Suppression;
import com.broadcastmail.common.suppressions.SuppressionsRepository;
import com.broadcastmail.common.usage.RecipientUsage;
import com.broadcastmail.common.usage.RecipientUsageRepository;
import com.broadcastmail.worker.IntegrationTest;
import com.broadcastmail.worker.TestContainersConfiguration;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(TestContainersConfiguration.class)
class BatchPersistenceServiceIntegrationTest extends IntegrationTest {

    @Autowired private BatchPersistenceService batchPersistenceService;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignRecipientRepository campaignRecipientRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private RecipientUsageRepository recipientUsageRepository;
    @Autowired private SuppressionsRepository suppressionsRepository;
    @MockitoSpyBean private OutboxEntryRepository outboxEntryRepository;

    private Campaign campaign;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(CampaignTestFixtures.account().build());
        Connection connection = connectionRepository.save(CampaignTestFixtures.connection(account.getId()).build());
        campaign = campaignRepository.save(
                CampaignTestFixtures.draftCampaign(account.getId(), connection.getId()).build());
    }

    @AfterEach
    void tearDown() {
        suppressionsRepository.deleteAll();
        recipientUsageRepository.deleteAll();
        campaignRecipientRepository.deleteAll();
        campaignRepository.deleteAll();
        connectionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldRecordPersistedRecipientsAsUsage() {
        // Given
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(3);

        // When
        batchPersistenceService.persistBatch(campaign.getId(), batch);

        // Then
        assertThat(recipientUsageRepository.findEmailsSince(campaign.getAccountId(), OffsetDateTime.now().minusDays(30)))
                .containsExactlyInAnyOrder("user1@example.com", "user2@example.com", "user3@example.com");
    }

    @Test
    void shouldNotRecordSuppressedAddressesAsUsage() {
        // Given
        suppressionsRepository.save(Suppression.builder()
                .accountId(campaign.getAccountId()).email("user2@example.com").reason("UNSUBSCRIBE").build());

        // When
        batchPersistenceService.persistBatch(campaign.getId(), CampaignTestFixtures.recipientRows(3));

        // Then
        assertThat(recipientUsageRepository.findEmailsSince(campaign.getAccountId(), OffsetDateTime.now().minusDays(30)))
                .containsExactlyInAnyOrder("user1@example.com", "user3@example.com");
    }

    @Test
    void shouldCountAnAddressOnceWhenItWasAlreadyUsedThisPeriod() {
        // Given
        batchPersistenceService.persistBatch(campaign.getId(), CampaignTestFixtures.recipientRows(2));
        OffsetDateTime tenDaysAgo = OffsetDateTime.now().minusDays(10);
        recipientUsageRepository.saveAll(List.of(
                new RecipientUsage(campaign.getAccountId(), "user1@example.com", tenDaysAgo),
                new RecipientUsage(campaign.getAccountId(), "user2@example.com", tenDaysAgo)));

        // When: a second campaign reaches the same addresses
        Campaign second = campaignRepository.save(CampaignTestFixtures.draftCampaign(campaign.getAccountId(), campaign.getConnectionId()).build());
        batchPersistenceService.persistBatch(second.getId(), CampaignTestFixtures.recipientRows(2));

        // Then: still 2 addresses, and the first-use date wasn't moved
        assertThat(recipientUsageRepository.countSince(campaign.getAccountId(), OffsetDateTime.now().minusDays(30))).isEqualTo(2);
        assertThat(recipientUsageRepository.findAll()).hasSize(2)
                .allSatisfy(usage -> assertThat(usage.getFirstUsedAt()).isBefore(OffsetDateTime.now().minusDays(9)));
    }

    @Test
    void shouldCountAnAddressAsNewAgainOnceItsPeriodExpired() {
        // Given: used 40 days ago
        batchPersistenceService.persistBatch(campaign.getId(), CampaignTestFixtures.recipientRows(1));
        recipientUsageRepository.save(new RecipientUsage(campaign.getAccountId(), "user1@example.com", OffsetDateTime.now().minusDays(40)));
        Campaign second = campaignRepository.save(CampaignTestFixtures.draftCampaign(campaign.getAccountId(), campaign.getConnectionId()).build());

        // When
        batchPersistenceService.persistBatch(second.getId(), CampaignTestFixtures.recipientRows(1));

        // Then
        assertThat(recipientUsageRepository.countSince(campaign.getAccountId(), OffsetDateTime.now().minusDays(30))).isEqualTo(1);
    }

    @Test
    void shouldCountSharedAddressOnceWhenTwoUsersHaveTheSameEmail() {
        // Given
        List<RecipientRow> batch = List.of(new RecipientRow("u1", "shared@example.com"), new RecipientRow("u2", "shared@example.com"));

        // When
        batchPersistenceService.persistBatch(campaign.getId(), batch);

        // Then
        assertThat(recipientUsageRepository.countSince(campaign.getAccountId(), OffsetDateTime.now().minusDays(30))).isEqualTo(1);
    }

    @Test
    void shouldRollbackRecipientsIfOutboxCreationFails() {
        // Given
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(1);
        doThrow(new RuntimeException("outbox failure")).when(outboxEntryRepository).saveAll(any());

        // When
        ThrowableAssert.ThrowingCallable action = () ->
                batchPersistenceService.persistBatch(campaign.getId(), batch);

        // Then
        assertThatThrownBy(action).isInstanceOf(RuntimeException.class);
        assertThat(campaignRecipientRepository.findByCampaignId(campaign.getId(), Pageable.unpaged()))
                .isEmpty();
        assertThat(recipientUsageRepository.count()).isZero();
    }

    @Test
    void shouldPersistRecipientsAndOutboxEntries() {
        // Given
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(2);

        // When
        batchPersistenceService.persistBatch(campaign.getId(), batch);

        // Then
        assertThat(campaignRecipientRepository.findByCampaignId(campaign.getId(), Pageable.unpaged()))
                .hasSize(2);
    }

    @Test
    void shouldSkipOutboxForAlreadyExistingRecipients() {
        // Given
        List<RecipientRow> batch = CampaignTestFixtures.recipientRows(2);
        batchPersistenceService.persistBatch(campaign.getId(), batch);

        // When
        batchPersistenceService.persistBatch(campaign.getId(), batch);

        // Then
        assertThat(campaignRecipientRepository
                .findByCampaignId(campaign.getId(), Pageable.unpaged()))
                .hasSize(2);

        assertThat(outboxEntryRepository.findAll())
                .hasSize(2);
    }
}