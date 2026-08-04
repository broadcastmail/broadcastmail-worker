package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class OutboxPoller {
    private final OutboxEntryRepository outboxEntryRepository;
    public List<OutboxEntry> pollAndClaim() {
        List<OutboxEntry> pending = outboxEntryRepository.pollPending();
        pending.forEach(entry -> {
            entry.setStatus(OutboxStatus.PROCESSING);
            outboxEntryRepository.save(entry);
            entry.setLastAttemptedAt(OffsetDateTime.now(ZoneId.systemDefault()));

        });
        return pending;
    }
}
