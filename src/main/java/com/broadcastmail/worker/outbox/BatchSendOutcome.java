package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.recipient.CampaignRecipient;

public record BatchSendOutcome(
        CampaignRecipient recipient,
        String messageId,
        String errorMessage
) {
    boolean succeeded() {
        return messageId != null;
    }
}
