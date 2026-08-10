package com.broadcastmail.worker.jobs;


import com.broadcastmail.common.account.Account;
import com.broadcastmail.common.account.AccountRepository;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.worker.TestContainersConfiguration;
import com.broadcastmail.worker.support.CampaignTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestContainersConfiguration.class)
class RetentionCleanupJobIntegrationTest {

    @Autowired private RetentionCleanupJob retentionCleanupJob;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ConnectionRepository connectionRepository;

    private Account account;
    private Connection connection;

    @BeforeEach
    void setUp() {
        account = accountRepository.save(CampaignTestFixtures.account().build());
        connection = connectionRepository.save(CampaignTestFixtures.connection(account.getId()).build());
    }

    @AfterEach
    void tearDown() {
        campaignRepository.deleteAll();
        connectionRepository.deleteAll();
        accountRepository.deleteAll();
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
        account.setPlan("pro");
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
}
