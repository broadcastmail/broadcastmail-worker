package com.broadcastmail.worker.jobs;

import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.worker.resolution.ResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResolutionJob {

    private final CampaignRepository campaignRepository;
    private final ResolutionService resolutionService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public void trigger() {
        pending.set(true);
        if (!running.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("resolution").start(() -> {
            try {
                while (pending.getAndSet(false)) {
                    resolvePending();
                }
            } finally {
                running.set(false);
            }
            // A trigger between the last check and releasing the flag would otherwise wait for the next poll.
            if (pending.get()) trigger();
        });
    }

    /** Fallback: picks up anything the campaign_status notify missed (worker down, listener reconnecting). */
    @Scheduled(fixedDelay = 30000)
    public void poll() {
        trigger();
    }

    void resolvePending() {
        try {
            campaignRepository.findAllByStatus(CampaignStatus.RESOLVING)
                    .stream()
                    .limit(3)
                    .forEach(resolutionService::resolve);
        } catch (Exception e) {
            log.warn("Resolution run failed: {}", e.getMessage());
        }
    }
}
