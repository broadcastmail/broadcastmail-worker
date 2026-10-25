package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class OutboxPoller {
    private final OutboxEntryRepository outboxEntryRepository;

    @Value("${app.outbox.poll-limit:100}")
    private int pollLimit;

    public List<OutboxEntry> pollAndClaim() {
        List<OutboxEntry> pending = outboxEntryRepository.pollPending(pollLimit);
        pending.forEach(entry -> {
            entry.setStatus(OutboxStatus.PROCESSING);
            outboxEntryRepository.save(entry);
            entry.setLastAttemptedAt(OffsetDateTime.now(ZoneId.systemDefault()));

        });
        return pending;
    }
}
