package com.broadcastmail.worker.account;

import com.broadcastmail.common.account.Account;
import com.broadcastmail.common.account.AccountRepository;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.worker.TestContainersConfiguration;
import com.broadcastmail.worker.common.exceptions.PlanLimitExceededException;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(TestContainersConfiguration.class)
class AccountPlanServiceIntegrationTest {

    @Autowired private AccountPlanService accountPlanService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignRecipientRepository campaignRecipientRepository;

    private Account account;
    private Connection connection;

    @BeforeEach
    void setUp() {
        account = accountRepository.save(CampaignTestFixtures.account().build());
        connection = connectionRepository.save(CampaignTestFixtures.connection(account.getId()).build());
    }

    @AfterEach
    void tearDown() {
        campaignRecipientRepository.deleteAll();
        campaignRepository.deleteAll();
        connectionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldPassWhenNoRecipientsThisPeriod() {
        // Given — no campaign_recipients in DB

        // When / Then
        assertThatNoException().isThrownBy(() -> accountPlanService.checkRecipientLimit(account.getId()));
    }

    @Test
    void shouldCountUniqueRecipientsAcrossCampaigns() {
        // Given
        Campaign c1 = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT).build());
        Campaign c2 = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT).build());

        saveRecipient(c1.getId(), "user-1", "shared@example.com");
        saveRecipient(c2.getId(), "user-1", "shared@example.com");
        saveRecipient(c2.getId(), "user-2", "other@example.com");

        // When / Then
        assertThatNoException().isThrownBy(() -> accountPlanService.checkRecipientLimit(account.getId()));
    }

    @Test
    void shouldThrowWhenUniqueRecipientsExceed500() {
        // Given
        Campaign campaign = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT).build());

        for (int i = 1; i <= 501; i++) {
            saveRecipient(campaign.getId(), "user-" + i, "user" + i + "@example.com");
        }

        // When / Then
        assertThatThrownBy(() -> accountPlanService.checkRecipientLimit(account.getId()))
                .isInstanceOf(PlanLimitExceededException.class);
    }

    @Test
    void shouldNotCountRecipientsFromDifferentAccount() {
        // Given — 501 recipients belonging to a different account
        Account other = accountRepository.save(CampaignTestFixtures.account()
                .email("other@example.com")
                .apiKeyHash("different-hash")
                .build());
        Connection otherConnection = connectionRepository.save(
                CampaignTestFixtures.connection(other.getId()).build());
        Campaign otherCampaign = campaignRepository.save(
                CampaignTestFixtures.draftCampaign(other.getId(), otherConnection.getId())
                        .status(CampaignStatus.SENT).build());

        for (int i = 1; i <= 501; i++) {
            saveRecipient(otherCampaign.getId(), "user-" + i, "user" + i + "@example.com");
        }

        // When / Then — our account has no recipients, should pass
        assertThatNoException().isThrownBy(() -> accountPlanService.checkRecipientLimit(account.getId()));
    }

    @Test
    void shouldNotCountRecipientsFromOlderThan30Days() {
        // Given — 501 recipients inserted now (created_at = now)
        Campaign campaign = campaignRepository.save(CampaignTestFixtures.draftCampaign(account.getId(), connection.getId())
                .status(CampaignStatus.SENT).build());

        for (int i = 1; i <= 501; i++) {
            saveRecipient(campaign.getId(), "user-" + i, "user" + i + "@example.com");
        }

        // When
        long count = campaignRecipientRepository.countUniqueRecipientsSince(
                account.getId(), OffsetDateTime.now().minusDays(30));

        // Then
        long countFuture = campaignRecipientRepository.countUniqueRecipientsSince(
                account.getId(), OffsetDateTime.now().plusDays(1));

        assertThat(count).isEqualTo(501);
        assertThat(countFuture).isZero();
    }

    private void saveRecipient(UUID campaignId, String userId, String email) {
        campaignRecipientRepository.save(CampaignRecipient.builder()
                .campaignId(campaignId)
                .externalUserId(userId)
                .email(email)
                .status(RecipientStatus.QUEUED)
                .idempotencyKey(campaignId + ":" + userId)
                .build());
    }
}
