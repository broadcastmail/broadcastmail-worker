package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.outbox.OutboxEntry;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import com.broadcastmail.common.outbox.OutboxStatus;
import com.broadcastmail.worker.common.exceptions.EmailSendException;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
import com.broadcastmail.worker.common.exceptions.ResendThrottledException;
import com.broadcastmail.worker.resolution.TemplateAttributeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class OutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private final EmailSendService emailSendService;
    private final OutboxEntryRepository outboxEntryRepository;
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final CampaignCompletionService campaignCompletionService;
    private final CampaignRepository campaignRepository;
    private final TemplateAttributeService templateAttributeService;

    private static final int RESEND_BATCH_MAX = 100;
    private static final int MAX_CAMPAIGN_DAYS = 500;
    private static final int THROTTLE_RETRY_SECONDS = 2;
    private static final int MAX_REASON_LENGTH = 255;

    public void processAll(List<OutboxEntry> entries) {
        List<UUID> recipientIds = entries.stream().map(OutboxEntry::getCampaignRecipientId).toList();
        Map<UUID, CampaignRecipient> recipientMap = campaignRecipientRepository
                .findAllById(recipientIds).stream()
                .collect(Collectors.toMap(CampaignRecipient::getId, r -> r));
        var grouped = entries.stream()
                .filter(e -> recipientMap.containsKey(e.getCampaignRecipientId()))
                .collect(Collectors.groupingBy(e -> recipientMap.get(e.getCampaignRecipientId()).getCampaignId()));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            grouped.forEach((campaignId, batch) ->
                    executor.submit(() -> {
                        try {
                            processCampaignBatch(batch, recipientMap);
                        } catch (Exception e) {
                            log.error("processCampaignBatch failed for campaign {}", campaignId, e);
                        }
                    }));
        }
    }

    void processCampaignBatch(List<OutboxEntry> entries, Map<UUID, CampaignRecipient> recipientMap) {
        List<Map.Entry<OutboxEntry, CampaignRecipient>> pairs = entries.stream()
                .map(e -> Map.entry(e, recipientMap.get(e.getCampaignRecipientId())))
                .filter(p -> p.getValue() != null)
                .toList();

        if (pairs.isEmpty()) return;

        UUID campaignId = pairs.get(0).getValue().getCampaignId();
        try {
            sendCampaignBatch(pairs, campaignId);
        } catch (RuntimeException e) {
            // Otherwise the rows stay PROCESSING, get reset every few minutes and fail the same way forever.
            log.error("Sending failed for campaign {}; counting it as a failed attempt", campaignId, e);
            failUnfinished(pairs, campaignId, e);
        }
    }

    private void failUnfinished(List<Map.Entry<OutboxEntry, CampaignRecipient>> pairs, UUID campaignId, RuntimeException e) {
        String reason = truncate("Send failed: " + e.getMessage());
        boolean anyFailed = false;
        for (Map.Entry<OutboxEntry, CampaignRecipient> pair : pairs) {
            if (pair.getKey().getStatus() == OutboxStatus.PROCESSING
                    && handleEmailFailure(pair.getKey(), pair.getValue(), reason)) {
                anyFailed = true;
            }
        }
        if (anyFailed) {
            campaignRecipientRepository.flush();
            campaignCompletionService.checkAndComplete(campaignId);
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_REASON_LENGTH ? s : s.substring(0, MAX_REASON_LENGTH);
    }

    private void sendCampaignBatch(List<Map.Entry<OutboxEntry, CampaignRecipient>> pairs, UUID campaignId) {
        boolean anyFailed = false;
        List<Map.Entry<OutboxEntry, CampaignRecipient>> toSend = pairs;

        OptionalInt quota = emailSendService.getRemainingDailyQuota(campaignId);
        if (quota.isPresent() && quota.getAsInt() < pairs.size()) {
            int remaining = quota.getAsInt();
            if (remaining == 0) {
                boolean failed = rescheduleAll(pairs);
                markCampaignRateLimited(campaignId);
                if (failed) {
                    campaignRecipientRepository.flush();
                    campaignCompletionService.checkAndComplete(campaignId);
                }
                return;
            }
            toSend = pairs.subList(0, remaining);
            anyFailed = rescheduleAll(pairs.subList(remaining, pairs.size()));
            markCampaignRateLimited(campaignId);
        }

        Map<String, Map<String, Object>> campaignAttrs = templateAttributeService.getForCampaign(campaignId);
        ChunkResult result = sendChunks(toSend, campaignAttrs, campaignId);
        anyFailed |= result.anyFailed();

        if (anyFailed) campaignRecipientRepository.flush();
        if (!result.rateLimited() || anyFailed) campaignCompletionService.checkAndComplete(campaignId);
    }

    private ChunkResult sendChunks(List<Map.Entry<OutboxEntry, CampaignRecipient>> toSend,
                                   Map<String, Map<String, Object>> campaignAttrs,
                                   UUID campaignId) {
        boolean anyFailed = false;
        for (int chunkStart = 0; chunkStart < toSend.size(); chunkStart += RESEND_BATCH_MAX) {
            List<Map.Entry<OutboxEntry, CampaignRecipient>> chunk =
                    toSend.subList(chunkStart, Math.min(chunkStart + RESEND_BATCH_MAX, toSend.size()));
            try {
                List<BatchSendOutcome> outcomes = emailSendService.sendEmailBatch(
                        chunk.stream().map(Map.Entry::getValue).toList(), campaignAttrs);
                if (processOutcomes(outcomes, chunk, campaignId)) anyFailed = true;
            } catch (ResendThrottledException _) {
                // Per-second limit: retry shortly, without using up an attempt or waiting for tomorrow.
                retrySoon(toSend.subList(chunkStart, toSend.size()));
                return new ChunkResult(true, anyFailed);
            } catch (ResendRateLimitException _) {
                emailSendService.evictDailyQuota(campaignId);
                if (rescheduleAll(toSend.subList(chunkStart, toSend.size()))) anyFailed = true;
                markCampaignRateLimited(campaignId);
                return new ChunkResult(true, anyFailed);
            } catch (EmailSendException e) {
                if (handleChunkFailure(chunk, e.getMessage())) anyFailed = true;
            }
        }
        return new ChunkResult(false, anyFailed);
    }

    private record ChunkResult(boolean rateLimited, boolean anyFailed) {}

    private void retrySoon(List<Map.Entry<OutboxEntry, CampaignRecipient>> pairs) {
        OffsetDateTime at = OffsetDateTime.now(ZoneId.systemDefault()).plusSeconds(THROTTLE_RETRY_SECONDS);
        for (Map.Entry<OutboxEntry, CampaignRecipient> pair : pairs) {
            OutboxEntry entry = pair.getKey();
            entry.setStatus(OutboxStatus.PENDING);
            entry.setNextAttemptAt(at);
            outboxEntryRepository.save(entry);
        }
    }

    private boolean rescheduleAll(List<Map.Entry<OutboxEntry, CampaignRecipient>> pairs) {
        boolean anyFailed = false;
        for (Map.Entry<OutboxEntry, CampaignRecipient> pair : pairs) {
            if (rescheduleForNextDay(pair.getKey(), pair.getValue())) anyFailed = true;
        }
        return anyFailed;
    }

    private boolean processOutcomes(List<BatchSendOutcome> outcomes,
                                    List<Map.Entry<OutboxEntry, CampaignRecipient>> chunk,
                                    UUID campaignId) {
        boolean anyFailed = false;
        for (int i = 0; i < outcomes.size(); i++) {
            BatchSendOutcome outcome = outcomes.get(i);
            OutboxEntry entry = chunk.get(i).getKey();
            if (outcome.suppressed()) {
                entry.setStatus(OutboxStatus.DONE);
                outboxEntryRepository.save(entry);
                outcome.recipient().setStatus(RecipientStatus.FAILED);
                outcome.recipient().setFailedReason("suppressed");
                campaignRecipientRepository.save(outcome.recipient());
                anyFailed = true;
            } else if (outcome.succeeded()) {
                markSent(entry, outcome, campaignId);
            } else {
                if (handleEmailFailure(entry, outcome.recipient(), outcome.errorMessage())) anyFailed = true;
            }
        }
        return anyFailed;
    }

    private void markSent(OutboxEntry entry, BatchSendOutcome outcome, UUID campaignId) {
        entry.setStatus(OutboxStatus.DONE);
        outboxEntryRepository.save(entry);
        CampaignRecipient recipient = outcome.recipient();
        recipient.setStatus(RecipientStatus.SENT);
        recipient.setResendMessageId(outcome.messageId());
        recipient.setSentAt(OffsetDateTime.now(ZoneId.systemDefault()));
        campaignRecipientRepository.save(recipient);
        campaignRepository.incrementSentCount(campaignId);
    }

    private boolean handleChunkFailure(List<Map.Entry<OutboxEntry, CampaignRecipient>> chunk, String reason) {
        boolean anyFailed = false;
        for (Map.Entry<OutboxEntry, CampaignRecipient> pair : chunk) {
            if (handleEmailFailure(pair.getKey(), pair.getValue(), reason)) anyFailed = true;
        }
        return anyFailed;
    }

    private boolean rescheduleForNextDay(OutboxEntry entry, CampaignRecipient recipient) {
        if (entry.getCreatedAt().plusDays(MAX_CAMPAIGN_DAYS).isBefore(OffsetDateTime.now(ZoneId.systemDefault()))) {
            entry.setStatus(OutboxStatus.FAILED);
            recipient.setStatus(RecipientStatus.FAILED);
            recipient.setFailedReason("Campaign exceeded maximum duration of " + MAX_CAMPAIGN_DAYS + " days");
            campaignRecipientRepository.save(recipient);
            outboxEntryRepository.save(entry);
            return true;
        }
        entry.setStatus(OutboxStatus.PENDING);
        // Resend's daily quota resets at midnight UTC, whatever the host's time zone is.
        entry.setNextAttemptAt(
                LocalDate.now(ZoneOffset.UTC)
                        .plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toOffsetDateTime()
        );
        outboxEntryRepository.save(entry);
        return false;
    }

    private void markCampaignRateLimited(UUID campaignId) {
        campaignRepository.findById(campaignId).ifPresent(c -> {
            if (!"RATE_LIMITED".equals(c.getFailureReason())) {
                c.setFailureReason("RATE_LIMITED");
                campaignRepository.save(c);
            }
        });
    }

    private boolean handleEmailFailure(OutboxEntry entry, CampaignRecipient recipient, String reason) {
        entry.setAttempts(entry.getAttempts() + 1);
        entry.setLastAttemptedAt(OffsetDateTime.now(ZoneId.systemDefault()));

        if (entry.getAttempts() < 3) {
            entry.setStatus(OutboxStatus.PENDING);
            int backoffMinutes = switch (entry.getAttempts()) {
                case 1 -> 1;
                case 2 -> 5;
                default -> 15;
            };
            entry.setNextAttemptAt(OffsetDateTime.now(ZoneId.systemDefault()).plusMinutes(backoffMinutes));
            outboxEntryRepository.save(entry);
            return false;
        } else {
            entry.setStatus(OutboxStatus.FAILED);
            recipient.setStatus(RecipientStatus.FAILED);
            recipient.setFailedReason(reason);
            campaignRecipientRepository.save(recipient);
            outboxEntryRepository.save(entry);
            return true;
        }
    }
}
