package com.broadcastmail.worker.worker;


import com.broadcastmail.common.campaign.recipient.CampaignRecipient;

public record SendResult(
        String messageId, CampaignRecipient recipient
) {
}
