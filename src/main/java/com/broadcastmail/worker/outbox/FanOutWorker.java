package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.outbox.OutboxEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class FanOutWorker {

    private final OutboxPoller outboxPoller;
    private final OutboxProcessor outboxProcessor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Triggered immediately by OutboxNotifyListener on pg_notify. */
    public void trigger() {
        if (!running.compareAndSet(false, true)) return;
        try {
            List<OutboxEntry> claimed = outboxPoller.pollAndClaim();
            if (!claimed.isEmpty()) outboxProcessor.processAll(claimed);
        } finally {
            running.set(false);
        }
    }

    /** Fallback: picks up rescheduled entries and anything the notify missed. */
    @Scheduled(fixedDelay = 60000)
    public void poll() {
        trigger();
    }
}
