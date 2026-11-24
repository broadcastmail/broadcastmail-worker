package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.outbox.OutboxEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;


@Slf4j
@Service
@RequiredArgsConstructor
public class SendingTimeoutService {

    static final Duration BASE_TIMEOUT = Duration.ofMinutes(15);
    static final String TIMEOUT_REASON = "Sending timed out before the campaign finished";

    private final CampaignRepository campaignRepository;
    private final OutboxEntryRepository outboxEntryRepository;
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final CampaignCompletionService campaignCompletionService;


    @Value("${app.outbox.poll-limit:1000}")
    private int pollLimit;

    /** A big campaign gets longer than a small one: BASE_TIMEOUT plus an allowance per recipient. */
    public List<UUID> findTimedOut() {
        return campaignRepository.findTimedOutSendingIds((int) BASE_TIMEOUT.toMinutes(), 2.0 * 60 / pollLimit);
    }

    /** Fails what is still unsent and lets completion pick SENT, PARTIALLY_FAILED or FAILED from the totals. */
    @Transactional
    public void failUnsent(UUID campaignId) {
        outboxEntryRepository.failUnsentByCampaignId(campaignId);
        int failed = campaignRecipientRepository.failQueuedByCampaignId(campaignId, TIMEOUT_REASON);
        log.warn("Sending timed out for campaign {}; failed {} unsent recipients", campaignId, failed);
        campaignCompletionService.checkAndComplete(campaignId);
    }
}
