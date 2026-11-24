package com.broadcastmail.worker.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SendingTimeoutJob {
    private final SendingTimeoutService sendingTimeoutService;

    @Scheduled(fixedDelay = 300000)
    public void failTimedOutCampaigns() {
        for (UUID campaignId : sendingTimeoutService.findTimedOut()) {
            try {
                sendingTimeoutService.failUnsent(campaignId);
            } catch (Exception e) {
                // One bad campaign must not keep the others from timing out.
                log.error("Could not time out campaign {}", campaignId, e);
            }
        }
    }
}
