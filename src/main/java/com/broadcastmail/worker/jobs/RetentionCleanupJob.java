package com.broadcastmail.worker.jobs;

import com.broadcastmail.common.account.plan.Plan;
import com.broadcastmail.common.campaign.CampaignRepository;
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
    private final CampaignRepository campaignRepository;

    @Transactional
    @Scheduled(cron = "0 0 2 * * *")

    public void run() {
        for (Plan plan : Plan.values()) {
            OffsetDateTime cutoff = OffsetDateTime.now(ZoneId.systemDefault())
                    .minusDays(plan.strategy().retentionDays());
            campaignRepository.deleteByPlanAndSentAtBefore(plan.name(), cutoff);
        }
        log.info("RetentionCleanupJob — deleted campaigns older than retention window");
    }
}
