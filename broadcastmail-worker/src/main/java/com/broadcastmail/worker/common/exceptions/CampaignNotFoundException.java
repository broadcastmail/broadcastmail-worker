package com.broadcastmail.worker.common.exceptions;

import lombok.AllArgsConstructor;

import java.util.UUID;

@AllArgsConstructor
public class CampaignNotFoundException extends RuntimeException {
    public CampaignNotFoundException(UUID campaignId) {
        super("Campaign not found with an ID: " + campaignId);
    }
}
