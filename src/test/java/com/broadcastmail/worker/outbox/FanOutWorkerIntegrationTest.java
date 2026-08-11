package com.broadcastmail.worker.outbox;


import com.broadcastmail.common.account.Account;
import com.broadcastmail.common.account.AccountRepository;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.worker.TestContainersConfiguration;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.resend.ResendClient;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(TestContainersConfiguration.class)
class FanOutWorkerIntegrationTest {

    @Autowired
    private FanOutWorker fanOutWorker;

    @Autowired
    private OutboxEntryRepository outboxEntryRepository;

    @Autowired
    private CampaignRecipientRepository campaignRecipientRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private EmailProviderRepository emailProviderRepository;

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private ConnectionRepository connectionRepository;

    @MockitoSpyBean
    private ResendClient resendClient;

    private UUID accountId;
    private Campaign campaign;
    private CampaignRecipient recipient;
    private OutboxEntry outboxEntry;

    @Value("${app.encryption.key}")
    private String encryptionKey;

    @BeforeEach
    void setUp() {
        Account account = accountRepository.save(CampaignTestFixtures.account().build());
        accountId = account.getId();

        Connection connection = connectionRepository.save(
                CampaignTestFixtures.connection(accountId)
                        .encryptedCreds(SecurityUtil.encrypt("testpassword", CampaignTestFixtures.TEST_ENCRYPTION_KEY))
                        .build());
        UUID connectionId = connection.getId();

        emailProviderRepository.save(EmailProvider.builder()
                .accountId(accountId)
                .type("resend")
                .encryptedApiKey(SecurityUtil.encrypt("re_testkey123", encryptionKey))
                .fromAddress("hello@example.com")
                .build());

        campaign = campaignRepository.save(Campaign.builder()
                .accountId(accountId)
                .connectionId(connectionId)
                .name("Test Campaign")
                .subject("Hello")
                .bodyHtml("<p>Hi</p>")
                .status(CampaignStatus.SENDING)
                .build());

        recipient = campaignRecipientRepository.save(CampaignRecipient.builder()
                .campaignId(campaign.getId())
                .externalUserId("user-id-1")
                .email("user@example.com")
                .status(RecipientStatus.QUEUED)
                .idempotencyKey(campaign.getId() + ":user-id-1")
                .build());

        outboxEntry = outboxEntryRepository.save(OutboxEntry.builder()
                .campaignRecipientId(recipient.getId())
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .build());
    }

    @AfterEach
    void tearDown() {
        outboxEntryRepository.deleteAll();
        campaignRecipientRepository.deleteAll();
        campaignRepository.deleteAll();
        emailProviderRepository.deleteAll();
        connectionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldProcessAllPendingOutboxRowsOnPoll() {
        // Given
        doReturn("msg-123").when(resendClient).sendEmail(anyString(), any(), anyString());

        // When
        fanOutWorker.poll();

        // Then
        OutboxEntry processed = outboxEntryRepository.findById(outboxEntry.getId()).orElseThrow();

        CampaignRecipient processedRecipient = campaignRecipientRepository.findById(recipient.getId()).orElseThrow();

        assertThat(processed.getStatus()).isEqualTo(OutboxStatus.DONE);
        assertThat(processedRecipient.getStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(processedRecipient.getResendMessageId()).isEqualTo("msg-123");
    }

    @Test
    void shouldNotSendDuplicateEmailsWhenPolledConcurrently() throws Exception {
        doReturn("msg-123").when(resendClient).sendEmail(anyString(), any(), anyString());
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            CountDownLatch latch = new CountDownLatch(1);

            Future<?> task1 = executorService.submit(() -> {
                try {
                    latch.await();
                    fanOutWorker.poll();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });

            Future<?> task2 = executorService.submit(() -> {
                try {
                    latch.await();
                    fanOutWorker.poll();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });

            latch.countDown();
            task1.get();
            task2.get();
        }

        verify(resendClient, times(1)).sendEmail(anyString(), any(), anyString());
    }

    @Test
    void shouldUpdateCampaignStatusToSentAfterAllRowsProcessed() {
        // Given
        doReturn("msg-123").when(resendClient).sendEmail(anyString(), any(), anyString());

        // When
        fanOutWorker.poll();

        // Then
        Campaign processed = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(CampaignStatus.SENT);
    }
}
