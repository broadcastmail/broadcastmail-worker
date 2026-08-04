package com.broadcastmail.worker.outbox;


import com.broadcastmail.common.campaign.recipient.CampaignRecipient;

public record SendResult(
        String messageId, CampaignRecipient recipient
) {
}
