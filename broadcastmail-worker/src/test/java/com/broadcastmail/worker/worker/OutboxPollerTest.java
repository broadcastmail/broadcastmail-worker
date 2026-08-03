package com.broadcastmail.worker.worker;

import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEntryRepository outboxEntryRepository;

    @InjectMocks
    private OutboxPoller outboxPoller;

    private OutboxEntry pendingEntry() {
        return OutboxEntry.builder()
                .id(UUID.randomUUID())
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .build();
    }

    @Test
    void shouldMarkClaimedRowsAsProcessing() {
        // Given
        OutboxEntry entry = pendingEntry();
        when(outboxEntryRepository.pollPending()).thenReturn(List.of(entry));

        // When
        outboxPoller.pollAndClaim();

        // Then
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        verify(outboxEntryRepository).save(entry);
    }

    @Test
    void shouldSetLastAttemptedAtWhenClaiming() {
        // Given
        OutboxEntry entry = pendingEntry();
        when(outboxEntryRepository.pollPending()).thenReturn(List.of(entry));

        // When
        outboxPoller.pollAndClaim();

        // Then
        assertThat(entry.getLastAttemptedAt()).isNotNull();
    }

    @Test
    void shouldReturnEmptyListWhenNoPendingRows() {
        // Given
        when(outboxEntryRepository.pollPending()).thenReturn(List.of());

        // When
        List<OutboxEntry> result = outboxPoller.pollAndClaim();

        // Then
        assertThat(result).isEmpty();
    }
}