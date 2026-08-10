package com.broadcastmail.worker.jobs;

import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.worker.resolution.ResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class ResolutionJob {

    private final CampaignRepository campaignRepository;
    private final ResolutionService resolutionService;

    @Scheduled(fixedDelay = 5000)
    public void run() {
        campaignRepository.findAllByStatus(CampaignStatus.RESOLVING)
                .stream()
                .limit(3)
                .forEach(resolutionService::resolve);
    }
}
