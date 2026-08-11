package com.broadcastmail.worker.jobs;

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
        OffsetDateTime freeCutoff = OffsetDateTime.now(ZoneId.systemDefault()).minusDays(7);
        OffsetDateTime proCutoff = OffsetDateTime.now(ZoneId.systemDefault()).minusDays(90);

        campaignRepository.deleteByPlanAndSentAtBefore("free", freeCutoff);
        campaignRepository.deleteByPlanAndSentAtBefore("pro", proCutoff);

        log.info("RetentionCleanupJob — deleted campaigns older than retention window");
    }
}
