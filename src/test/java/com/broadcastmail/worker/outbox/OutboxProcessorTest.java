package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
import com.broadcastmail.worker.resolution.TemplateAttributeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    @Mock private EmailSendService emailSendService;
    @Mock private OutboxEntryRepository outboxEntryRepository;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private CampaignCompletionService campaignCompletionService;
    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateAttributeService templateAttributeService;

    @InjectMocks
    private OutboxProcessor outboxProcessor;

    private OutboxEntry outboxEntry;
    private CampaignRecipient recipient;
    private Map<UUID, CampaignRecipient> recipientMap;

    @BeforeEach
    void setUp() {
        recipient = CampaignRecipient.builder()
                .id(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .externalUserId("ext-user-123")
                .email("user@example.com")
                .status(RecipientStatus.QUEUED)
                .idempotencyKey("campaign-id:user-id")
                .build();

        outboxEntry = processingEntryWithAttempts(0);
        recipientMap = Map.of(recipient.getId(), recipient);

        when(emailSendService.getRemainingDailyQuota(recipient.getCampaignId()))
                .thenReturn(OptionalInt.empty());
        lenient().when(templateAttributeService.getForCampaign(recipient.getCampaignId()))
                .thenReturn(Map.of());
    }

    private OutboxEntry processingEntryWithAttempts(int attempts) {
        return OutboxEntry.builder()
                .id(UUID.randomUUID())
                .campaignRecipientId(recipient.getId())
                .status(OutboxStatus.PROCESSING)
                .attempts(attempts)
                .nextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .lastAttemptedAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .createdAt(OffsetDateTime.now(ZoneId.systemDefault()))
                .build();
    }

    private BatchSendOutcome success() {
        return new BatchSendOutcome(recipient, "msg-123", null);
    }

    private BatchSendOutcome failure(String reason) {
        return new BatchSendOutcome(recipient, null, reason);
    }

    @Test
    void shouldMarkOutboxDoneAndRecipientSentOnSuccessfulSend() {
        when(emailSendService.sendEmailBatch(any(), any())).thenReturn(List.of(success()));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.DONE);
        assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(recipient.getResendMessageId()).isEqualTo("msg-123");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void shouldDeliverEmailSuccessfullyRegardlessOfPriorAttempts(int attempts) {
        outboxEntry = processingEntryWithAttempts(attempts);
        when(emailSendService.sendEmailBatch(any(), any())).thenReturn(List.of(success()));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.DONE);
        assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.SENT);
    }

    @Test
    void shouldRescheduleOutboxForNextDayWithoutIncrementingAttemptsOnRateLimitException() {
        when(emailSendService.sendEmailBatch(any(), any()))
                .thenThrow(new ResendRateLimitException("Rate limit exceeded"));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEntry.getAttempts()).isZero();
        assertThat(outboxEntry.getNextAttemptAt().toLocalDate())
                .isEqualTo(LocalDate.now(ZoneId.systemDefault()).plusDays(1));
    }

    @Test
    void shouldRescheduleForNextDayWhenQuotaExhaustedBeforeSend() {
        when(emailSendService.getRemainingDailyQuota(recipient.getCampaignId()))
                .thenReturn(OptionalInt.of(0));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEntry.getNextAttemptAt().toLocalDate())
                .isEqualTo(LocalDate.now(ZoneId.systemDefault()).plusDays(1));
        verify(emailSendService, never()).sendEmailBatch(any(), any());
    }

    @Test
    void shouldMarkCampaignRateLimitedOnRateLimitException() {
        Campaign campaign = Campaign.builder().id(recipient.getCampaignId()).status(CampaignStatus.SENDING).build();
        when(emailSendService.sendEmailBatch(any(), any()))
                .thenThrow(new ResendRateLimitException("Rate limit exceeded"));
        when(campaignRepository.findById(recipient.getCampaignId())).thenReturn(Optional.of(campaign));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        assertThat(campaign.getFailureReason()).isEqualTo("RATE_LIMITED");
        verify(campaignRepository).save(campaign);
    }

    @Test
    void shouldNotSaveCampaignAgainIfAlreadyMarkedRateLimited() {
        Campaign campaign = Campaign.builder().id(recipient.getCampaignId()).status(CampaignStatus.SENDING)
                .failureReason("RATE_LIMITED").build();
        when(emailSendService.sendEmailBatch(any(), any()))
                .thenThrow(new ResendRateLimitException("Rate limit exceeded"));
        when(campaignRepository.findById(recipient.getCampaignId())).thenReturn(Optional.of(campaign));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        verify(campaignRepository, never()).save(campaign);
    }

    @Test
    void shouldIncrementAttemptsAndApplyBackoffOnTransientFailure() {
        when(emailSendService.sendEmailBatch(any(), any())).thenReturn(List.of(failure("Resend error")));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEntry.getAttempts()).isEqualTo(1);
        assertThat(outboxEntry.getNextAttemptAt()).isAfter(OffsetDateTime.now(ZoneId.systemDefault()));
    }

    @Test
    void shouldMarkOutboxAndRecipientFailedAfterMaxAttempts() {
        outboxEntry = processingEntryWithAttempts(3);
        when(emailSendService.sendEmailBatch(any(), any())).thenReturn(List.of(failure("Final failure")));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        assertThat(outboxEntry.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(recipient.getFailedReason()).isEqualTo("Final failure");
        InOrder inOrder = inOrder(campaignRecipientRepository, outboxEntryRepository, campaignCompletionService);
        inOrder.verify(campaignRecipientRepository).save(recipient);
        inOrder.verify(outboxEntryRepository).save(outboxEntry);
        inOrder.verify(campaignRecipientRepository).flush();
        inOrder.verify(campaignCompletionService).checkAndComplete(recipient.getCampaignId());
    }

    @Test
    void shouldIncrementCampaignSentCountOnSuccess() {
        when(emailSendService.sendEmailBatch(any(), any())).thenReturn(List.of(success()));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        verify(campaignRepository).incrementSentCount(recipient.getCampaignId());
    }

    @Test
    void shouldNotIncrementCampaignSentCountOnFailedSend() {
        outboxEntry = processingEntryWithAttempts(3);
        when(emailSendService.sendEmailBatch(any(), any())).thenReturn(List.of(failure("Final failure")));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        verify(campaignRepository, never()).incrementSentCount(any());
    }

    @Test
    void shouldSkipCampaignCompletionCheckWhenRateLimited() {
        when(emailSendService.sendEmailBatch(any(), any()))
                .thenThrow(new ResendRateLimitException("Rate limit exceeded"));

        outboxProcessor.processCampaignBatch(List.of(outboxEntry), recipientMap);

        verify(campaignCompletionService, never()).checkAndComplete(any());
    }
}
