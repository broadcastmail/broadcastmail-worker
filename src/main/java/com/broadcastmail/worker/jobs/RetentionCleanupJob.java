package com.broadcastmail.worker.jobs;

import com.broadcastmail.common.account.plan.Plan;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.usage.RecipientUsageRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionCleanupJob {
    // How long an emailed address counts toward the plan's recipient limit; independent of campaign retention.
    private static final int USAGE_PERIOD_DAYS = 30;

    private final CampaignRepository campaignRepository;
    private final RecipientUsageRepository recipientUsageRepository;

    @Transactional
    @Scheduled(cron = "0 0 2 * * *")

    public void run() {
        for (Plan plan : Plan.values()) {
            OffsetDateTime cutoff = OffsetDateTime.now(ZoneId.systemDefault())
                    .minusDays(plan.strategy().retentionDays());
            campaignRepository.deleteByPlanAndSentAtBefore(plan.name(), cutoff);
        }
        recipientUsageRepository.deleteOlderThan(OffsetDateTime.now(ZoneId.systemDefault()).minusDays(USAGE_PERIOD_DAYS));
        log.info("RetentionCleanupJob — deleted campaigns older than retention window");
    }
}
