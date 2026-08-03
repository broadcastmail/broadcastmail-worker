package com.broadcastmail.worker.worker;

import com.broadcastmail.common.outbox.OutboxEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class OutboxReconciliationJob {
    private final OutboxEntryRepository outboxEntryRepository;

    @Scheduled(fixedRate = 300000)
    public void resetStuckRows() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneId.systemDefault()).minusMinutes(5);
        outboxEntryRepository.resetStuckRows(cutoff);
    }
}
