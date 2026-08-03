package com.broadcastmail.worker.worker;

import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CampaignCompletionService {
    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository campaignRecipientRepository;

    public void  checkAndComplete(UUID campaignId)
    {
        long queued = campaignRecipientRepository.countByCampaignIdAndStatus(campaignId, RecipientStatus.QUEUED);        if (queued > 0) return;
        long total = campaignRecipientRepository.countByCampaignId(campaignId);
        long failed = campaignRecipientRepository.countByCampaignIdAndStatus(campaignId, RecipientStatus.FAILED);

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(()->new CampaignNotFoundException(campaignId));
        CampaignStatus finalStatus;
        if (failed == total) {
            finalStatus = CampaignStatus.FAILED;
        } else if ((double) failed / total > 0.1) {
            finalStatus = CampaignStatus.PARTIALLY_FAILED;
        } else {
            finalStatus = CampaignStatus.SENT;
        }
        campaign.setStatus(finalStatus);
        campaign.setSentAt(OffsetDateTime.now(ZoneId.systemDefault()));
        campaignRepository.save(campaign);

    }
}
