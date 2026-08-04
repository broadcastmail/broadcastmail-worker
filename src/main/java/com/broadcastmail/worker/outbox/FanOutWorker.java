package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.outbox.OutboxEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FanOutWorker {

    private final OutboxPoller outboxPoller;
    private final OutboxProcessor outboxProcessor;

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        List<OutboxEntry> claimed = outboxPoller.pollAndClaim();
        claimed.forEach(outboxProcessor::process);
    }
}
