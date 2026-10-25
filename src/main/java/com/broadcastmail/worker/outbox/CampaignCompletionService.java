package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.campaign.recipient.RecipientStatusCounts;
import com.broadcastmail.worker.resolution.TemplateAttributeService;
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
    private final EmailSendService emailSendService;
    private final TemplateAttributeService templateAttributeService;

    public void checkAndComplete(UUID campaignId) {
        RecipientStatusCounts counts = campaignRecipientRepository.countStatusesByCampaignId(campaignId);
        if (counts.getQueued() > 0) return;
        long total = counts.getTotal();
        long failed = counts.getFailed();

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));
        CampaignStatus finalStatus;
        if (failed == total) {
            finalStatus = CampaignStatus.FAILED;
        } else if ((double) failed / total > 0.1) {
            finalStatus = CampaignStatus.PARTIALLY_FAILED;
        } else {
            finalStatus = CampaignStatus.SENT;
        }
        campaign.setStatus(finalStatus);
        campaign.setFailedCount(Math.toIntExact(failed));
        campaign.setSentAt(OffsetDateTime.now(ZoneId.systemDefault()));
        campaign.setFailureReason(null);
        campaignRepository.save(campaign);

        emailSendService.evictCampaign(campaignId);
        templateAttributeService.invalidate(campaignId);
    }
}
