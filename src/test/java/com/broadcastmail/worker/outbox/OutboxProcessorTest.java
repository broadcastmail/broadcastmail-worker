package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.common.exceptions.EmailSendException;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    @Mock
    private EmailSendService emailSendService;

    @Mock
    private CampaignRecipientRepository campaignRecipientRepository;

    @Mock
    private CampaignCompletionService campaignCompletionService;

    @InjectMocks
    private OutboxProcessor outboxProcessor;

    private OutboxEntry outboxEntry;
    private CampaignRecipient recipient;

    @BeforeEach
    void setUp() {
        recipient = CampaignRecipient.builder()
                .id(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .email("user@example.com")
                .status(RecipientStatus.QUEUED)
                .idempotencyKey("campaign-id:user-id")
                .build();

        outboxEntry = processingEntryWithAttempts(0);

        when(campaignRecipientRepository.findById(outboxEntry.getCampaignRecipientId()))
                .thenReturn(Optional.of(recipient));
    }

    private OutboxEntry processingEntryWithAttempts(int attempts) {
        return OutboxEntry.builder()
                .id(UUID.randomUUID())
                .campaignRecipientId(recipient.getId())
                .status(OutboxStatus.PROCESSING)
                .attempts(attempts)
                .nextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .lastAttemptedAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .build();
    }

    @Test
    void shouldMarkOutboxDoneAndRecipientSentOnSuccessfulSend() {
        // Given
        when(emailSendService.sendEmail(recipient))
                .thenReturn(new SendResult("msg-123", recipient));

        // When
        outboxProcessor.process(outboxEntry);

        // Then
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.DONE);
        assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(recipient.getResendMessageId()).isEqualTo("msg-123");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void shouldDeliverEmailSuccessfullyRegardlessOfPriorAttempts(int attempts) {
        // Given
        outboxEntry = processingEntryWithAttempts(attempts);
        when(emailSendService.sendEmail(recipient))
                .thenReturn(new SendResult("msg-123", recipient));

        // When
        outboxProcessor.process(outboxEntry);

        // Then
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.DONE);
        assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.SENT);
    }

    @Test
    void shouldRescheduleOutboxForNextDayWithoutIncrementingAttemptsOnRateLimit() {
        // Given
        when(emailSendService.sendEmail(recipient))
                .thenThrow(new ResendRateLimitException("Rate limit exceeded"));

        // When
        outboxProcessor.process(outboxEntry);

        // Then
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEntry.getAttempts()).isZero();
        assertThat(outboxEntry.getNextAttemptAt().toLocalDate())
                .isEqualTo(LocalDate.now(ZoneId.systemDefault()).plusDays(1));
    }

    @Test
    void shouldIncrementAttemptsAndApplyExponentialBackoffOnTransientFailure() {
        // Given
        when(emailSendService.sendEmail(recipient))
                .thenThrow(new EmailSendException("Resend error"));

        // When
        outboxProcessor.process(outboxEntry);

        // Then
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEntry.getAttempts()).isEqualTo(1);
        assertThat(outboxEntry.getNextAttemptAt())
                .isAfter(OffsetDateTime.now(ZoneId.systemDefault()));
    }

    @Test
    void shouldMarkOutboxAndRecipientFailedAfterThreeFailedAttempts() {
        // Given
        outboxEntry = processingEntryWithAttempts(3);
        when(emailSendService.sendEmail(recipient))
                .thenThrow(new EmailSendException("Final failure"));

        // When
        outboxProcessor.process(outboxEntry);

        // Then
        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(recipient.getFailedReason()).isEqualTo("Final failure");
    }

    @Test
    void shouldSkipCampaignCompletionCheckWhenRateLimited() {
        // Given
        when(emailSendService.sendEmail(recipient))
                .thenThrow(new ResendRateLimitException("Rate limit exceeded"));

        // When
        outboxProcessor.process(outboxEntry);

        // Then
        verify(campaignCompletionService, never()).checkAndComplete(any());
    }
}