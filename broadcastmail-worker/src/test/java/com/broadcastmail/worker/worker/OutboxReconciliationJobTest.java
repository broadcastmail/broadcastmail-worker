package com.broadcastmail.worker.worker;

import com.broadcastmail.common.outbox.OutboxEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxReconciliationJobTest {

    @Mock
    private OutboxEntryRepository outboxEntryRepository;

    @InjectMocks
    private OutboxReconciliationJob outboxReconciliationJob;

    @Test
    void shouldResetProcessingRowsOlderThan5MinutesToPending() {
        // When
        outboxReconciliationJob.resetStuckRows();

        // Then
        verify(outboxEntryRepository).resetStuckRows(
                argThat(cutoff -> cutoff.isBefore(OffsetDateTime.now(ZoneId.systemDefault()).minusMinutes(4)))
        );
    }


}