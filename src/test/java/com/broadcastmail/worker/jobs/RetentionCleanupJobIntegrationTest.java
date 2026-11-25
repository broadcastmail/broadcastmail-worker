package com.broadcastmail.worker.jobs;


import com.broadcastmail.common.account.Account;
import com.broadcastmail.common.account.AccountRepository;
import com.broadcastmail.common.account.plan.Plan;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.common.usage.RecipientUsage;
import com.broadcastmail.common.usage.RecipientUsageRepository;
import com.broadcastmail.worker.IntegrationTest;
import com.broadcastmail.worker.TestContainersConfiguration;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestContainersConfiguration.class)
class RetentionCleanupJobIntegrationTest extends IntegrationTest {

    @Autowired private RetentionCleanupJob retentionCleanupJob;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private CampaignRecipientRepository campaignRecipientRepository;
    @Autowired private RecipientUsageRepository recipientUsageRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account account;
    private Connection connection;

    @BeforeEach
    void setUp() {
        account = accountRepository.save(CampaignTestFixtures.account().build());
        connection = connectionRepository.save(CampaignTestFixtures.connection(account.getId()).build());
    }

    @AfterEach
    void tearDown() {
        recipientUsageRepository.deleteAll();
        campaignRepository.deleteAll();
        connectionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldKeepCountingRecipientsTowardTheLimitAfterTheirCampaignIsDeleted() {
        // Given: a Free campaign sent 8 days ago whose recipients were recorded when it was resolved
        Campaign old = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT)
                .sentAt(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(8))
                .build());
        campaignRecipientRepository.save(CampaignRecipient.builder()
                .campaignId(old.getId())
                .externalUserId("user-1")
                .email("user1@example.com")
                .status(RecipientStatus.SENT)
                .idempotencyKey(old.getId() + ":user-1")
                .build());
        recipientUsageRepository.recordForCampaign(old.getId(), new String[]{"user-1"});

        // When
        retentionCleanupJob.run();

        // Then: the campaign and its recipients are gone, the usage is not
        assertThat(campaignRepository.findById(old.getId())).isEmpty();
        assertThat(campaignRecipientRepository.findByCampaignId(old.getId(), org.springframework.data.domain.Pageable.unpaged())).isEmpty();
        OffsetDateTime monthAgo = OffsetDateTime.now(ZoneId.systemDefault()).minusDays(30);
        assertThat(recipientUsageRepository.countSince(account.getId(), monthAgo)).isEqualTo(1);
        assertThat(recipientUsageRepository.findEmailsSince(account.getId(), monthAgo)).containsExactly("user1@example.com");
    }

    @Test
    void shouldDeleteUsageOlderThan30DaysAndKeepNewer() {
        // Given
        usedDaysAgo("old@example.com", 31);
        usedDaysAgo("recent@example.com", 29);

        // When
        retentionCleanupJob.run();

        // Then
        assertThat(recipientUsageRepository.findEmailsSince(account.getId(), OffsetDateTime.now(ZoneId.systemDefault()).minusDays(60)))
                .containsExactly("recent@example.com");
    }

    @Test
    void shouldDeleteFreeCampaignsOlderThan7Days() {
        // Given
        Campaign old = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT)
                .sentAt(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(8))
                .build());
        Campaign recent = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT)
                .sentAt(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(6))
                .build());

        // When
        retentionCleanupJob.run();

        // Then
        assertThat(campaignRepository.findById(old.getId())).isEmpty();
        assertThat(campaignRepository.findById(recent.getId())).isPresent();
    }

    @Test
    void shouldDeleteProCampaignsOlderThan90Days() {
        // Given
        account.setPlan(Plan.STARTER);
        accountRepository.save(account);

        Campaign old = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT)
                .sentAt(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(91))
                .build());
        Campaign recent = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT)
                .sentAt(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(89))
                .build());

        // When
        retentionCleanupJob.run();

        // Then
        assertThat(campaignRepository.findById(old.getId())).isEmpty();
        assertThat(campaignRepository.findById(recent.getId())).isPresent();
    }

    @Test
    void shouldNotDeleteDraftCampaigns() {
        // Given
        Campaign draft = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.DRAFT)
                .build());

        // When
        retentionCleanupJob.run();

        // Then
        assertThat(campaignRepository.findById(draft.getId())).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"FAILED", "PARTIALLY_FAILED"})
    void shouldDeleteFailedCampaignsOlderThanRetentionLikeSentOnes(CampaignStatus status) {
        // Given
        Campaign old = finishedDaysAgo(status, 8);
        Campaign recent = finishedDaysAgo(status, 6);
        campaignRecipientRepository.save(recipientOf(old));

        // When
        retentionCleanupJob.run();

        // Then: the campaign and the emails it held are gone
        assertThat(campaignRepository.findById(old.getId())).isEmpty();
        assertThat(campaignRecipientRepository.findByCampaignId(old.getId(), Pageable.unpaged())).isEmpty();
        assertThat(campaignRepository.findById(recent.getId())).isPresent();
    }

    @Test
    void shouldUseUpdatedAtForACampaignThatFailedBeforeSending() {
        // Given: failed during resolution, so it never got a sent_at
        Campaign old = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.FAILED)
                .build());
        Campaign recent = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.FAILED)
                .build());
        setUpdatedAtDaysAgo(old, 8);
        setUpdatedAtDaysAgo(recent, 6);

        // When
        retentionCleanupJob.run();

        // Then
        assertThat(campaignRepository.findById(old.getId())).isEmpty();
        assertThat(campaignRepository.findById(recent.getId())).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "RESOLVING", "SENDING"})
    void shouldKeepDraftAndInFlightCampaignsHoweverOld(CampaignStatus status) {
        // Given: includes a rate-limited campaign, which stays SENDING until it resumes
        Campaign campaign = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(status)
                .failureReason(status == CampaignStatus.SENDING ? "RATE_LIMITED" : null)
                .build());
        setUpdatedAtDaysAgo(campaign, 100);

        // When
        retentionCleanupJob.run();

        // Then
        assertThat(campaignRepository.findById(campaign.getId())).isPresent();
    }

    @Test
    void shouldDeleteTheOriginalOfARetriedCampaignAndKeepTheRetry() {
        // Given
        Campaign original = finishedDaysAgo(CampaignStatus.PARTIALLY_FAILED, 8);
        Campaign retry = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT)
                .sentAt(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(1))
                .retryOfCampaignId(original.getId())
                .build());

        // When
        retentionCleanupJob.run();

        // Then: only the lineage link is lost (retry_of_campaign_id is ON DELETE SET NULL)
        assertThat(campaignRepository.findById(original.getId())).isEmpty();
        assertThat(campaignRepository.findById(retry.getId()))
                .hasValueSatisfying(c -> assertThat(c.getRetryOfCampaignId()).isNull());
    }

    private Campaign finishedDaysAgo(CampaignStatus status, int daysAgo) {
        return campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(status)
                .sentAt(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(daysAgo))
                .build());
    }

    private CampaignRecipient recipientOf(Campaign campaign) {
        return CampaignRecipient.builder()
                .campaignId(campaign.getId())
                .externalUserId("user-1")
                .email("user1@example.com")
                .status(RecipientStatus.FAILED)
                .idempotencyKey(campaign.getId() + ":user-1")
                .build();
    }

    // @UpdateTimestamp overwrites updated_at on every save, so age it directly
    private void setUpdatedAtDaysAgo(Campaign campaign, int daysAgo) {
        jdbcTemplate.update("UPDATE campaigns SET updated_at = now() - make_interval(days => ?) WHERE id = ?",
                daysAgo, campaign.getId());
    }

    private void usedDaysAgo(String email, int daysAgo) {
        recipientUsageRepository.save(new RecipientUsage(account.getId(), email, OffsetDateTime.now(ZoneId.systemDefault()).minusDays(daysAgo)));
    }
}
