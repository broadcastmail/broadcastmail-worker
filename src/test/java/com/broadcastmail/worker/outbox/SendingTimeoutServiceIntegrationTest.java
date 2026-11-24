package com.broadcastmail.worker.outbox;

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
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.IntegrationTest;
import com.broadcastmail.worker.TestContainersConfiguration;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestContainersConfiguration.class)
class SendingTimeoutServiceIntegrationTest extends IntegrationTest {

    @Autowired private SendingTimeoutService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignRecipientRepository recipientRepository;
    @Autowired private OutboxEntryRepository outboxEntryRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ConnectionRepository connectionRepository;

    private UUID accountId;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(CampaignTestFixtures.account().build());
        accountId = account.getId();
        Connection connection = connectionRepository.save(CampaignTestFixtures.connection(accountId).build());
        connectionId = connection.getId();
    }

    @AfterEach
    void tearDown() {
        outboxEntryRepository.deleteAll();
        recipientRepository.deleteAll();
        campaignRepository.deleteAll();
        connectionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private Campaign sendingCampaign(String failureReason, int recipientCount, int minutesSinceUpdate) {
        Campaign campaign = campaignRepository.save(CampaignTestFixtures.draftCampaign(accountId, connectionId)
                .status(CampaignStatus.SENDING)
                .failureReason(failureReason)
                .recipientCount(recipientCount)
                .build());
        jdbcTemplate.update("UPDATE campaigns SET updated_at = now() - make_interval(mins => ?) WHERE id = ?",
                minutesSinceUpdate, campaign.getId());
        return campaign;
    }

    private CampaignRecipient recipient(Campaign campaign, String email, RecipientStatus status, OutboxStatus outbox) {
        CampaignRecipient recipient = recipientRepository.save(CampaignRecipient.builder()
                .campaignId(campaign.getId())
                .externalUserId(email)
                .email(email)
                .status(status)
                .idempotencyKey(campaign.getId() + ":" + email)
                .build());
        outboxEntryRepository.save(OutboxEntry.builder()
                .campaignRecipientId(recipient.getId())
                .status(outbox)
                .attempts(0)
                .nextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .build());
        return recipient;
    }

    @Test
    void findsOnlyCampaignsPastTheLimitThatAreNotRateLimited() {
        Campaign stuck = sendingCampaign(null, 10, 20);
        sendingCampaign(null, 10, 10);
        sendingCampaign("RATE_LIMITED", 10, 60 * 24 * 30);

        assertThat(service.findTimedOut()).containsExactly(stuck.getId());
    }

    @Test
    void biggerCampaignsGetMoreTime() {
        // 15 minutes plus 2 minutes per 1000 recipients: 100,000 recipients is 215 minutes.
        sendingCampaign(null, 100_000, 200);
        Campaign overdue = sendingCampaign(null, 100_000, 230);

        assertThat(service.findTimedOut()).containsExactly(overdue.getId());
    }

    @Test
    void aScheduledCampaignIsNotTimedOutWhileItWaitsForItsTime() {
        Campaign waiting = sendingCampaign(null, 10, 600);
        jdbcTemplate.update("UPDATE campaigns SET scheduled_at = now() + interval '1 hour' WHERE id = ?", waiting.getId());

        assertThat(service.findTimedOut()).isEmpty();
    }

    @Test
    void failsUnsentRecipientsKeepsSentOnesAndCompletesTheCampaign() {
        Campaign campaign = sendingCampaign(null, 3, 60);
        CampaignRecipient sent = recipient(campaign, "sent@example.com", RecipientStatus.SENT, OutboxStatus.DONE);
        CampaignRecipient waiting = recipient(campaign, "waiting@example.com", RecipientStatus.QUEUED, OutboxStatus.PENDING);
        CampaignRecipient inFlight = recipient(campaign, "flight@example.com", RecipientStatus.QUEUED, OutboxStatus.PROCESSING);

        service.failUnsent(campaign.getId());

        assertThat(recipientRepository.findById(sent.getId()).orElseThrow().getStatus()).isEqualTo(RecipientStatus.SENT);
        for (CampaignRecipient r : new CampaignRecipient[]{waiting, inFlight}) {
            CampaignRecipient reloaded = recipientRepository.findById(r.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(RecipientStatus.FAILED);
            assertThat(reloaded.getFailedReason()).isEqualTo(SendingTimeoutService.TIMEOUT_REASON);
        }
        assertThat(outboxEntryRepository.findAll())
                .filteredOn(o -> !o.getCampaignRecipientId().equals(sent.getId()))
                .allMatch(o -> o.getStatus() == OutboxStatus.FAILED);

        Campaign done = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(CampaignStatus.PARTIALLY_FAILED);
        assertThat(done.getFailedCount()).isEqualTo(2);
    }
}
