package com.broadcastmail.worker.resend;

import java.time.Duration;

/** Spaces calls evenly so at most {@code perSecond} start in any second. Thread-safe. */
final class RequestPacer {

    private final long intervalNanos;
    private long nextSlot = System.nanoTime();

    RequestPacer(int perSecond) {
        this.intervalNanos = 1_000_000_000L / perSecond;
    }

    /** Blocks until this caller's slot. */
    void acquire() {
        long wait = reserve();
        if (wait <= 0) return;
        try {
            Thread.sleep(Duration.ofNanos(wait));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized long reserve() {
        long now = System.nanoTime();
        long slot = Math.max(now, nextSlot);
        nextSlot = slot + intervalNanos;
        return slot - now;
    }
}
