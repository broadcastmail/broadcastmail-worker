package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.filter.CampaignFilter;
import com.broadcastmail.common.campaign.filter.CampaignFilterRepository;
import com.broadcastmail.common.campaign.filter.CampaignFilterSerializer;
import com.broadcastmail.common.campaign.filter.FilterQuery;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.worker.account.AccountPlanService;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.PlanLimitExceededException;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionService {
    private static final int BATCH_SIZE = 100;


    private final AccountPlanService accountPlanService;
    private final ConnectionRepository connectionRepository;
    private final CampaignFilterRepository filterRepository;
    private final CampaignFilterSerializer filterSerializer;
    private final BatchPersistenceService batchPersistenceService;
    private final ExternalRecipientQueryService externalRecipientQueryService;
    private final CampaignRepository campaignRepository;
    private final EncryptionProperties encryptionProperties;

    public void resolve(Campaign campaign) {
        try {
            Connection connection = connectionRepository.findByAccountId(campaign.getAccountId())
                    .orElseThrow(() -> new RuntimeException("Connection not found for account: " + campaign.getAccountId()));

            String rolePassword = SecurityUtil.decrypt(connection.getEncryptedCreds(), encryptionProperties.key());
            List<CampaignFilter> filters = filterRepository.findByCampaignId(campaign.getId());
            FilterQuery filterQuery = filterSerializer.serialize(filters);
            int totalResolved = 0;
            int offset = 0;
            List<RecipientRow> batch = fetchBatch(connection, rolePassword, filterQuery, offset);

            while (!batch.isEmpty()) {
                batchPersistenceService.persistBatch(campaign.getId(), batch);
                accountPlanService.checkRecipientLimit(campaign.getAccountId());
                totalResolved += batch.size();
                offset += BATCH_SIZE;
                batch = fetchBatch(connection, rolePassword, filterQuery, offset);
            }

            campaign.setStatus(CampaignStatus.SENDING);
            campaign.setRecipientCount(totalResolved);
            campaignRepository.save(campaign);

        } catch (PlanLimitExceededException _) {
            failCampaign(campaign);
            log.warn("Plan limit exceeded for campaign {}", campaign.getId());
        } catch (Exception e) {
            failCampaign(campaign);
            log.error("Resolution failed for campaign {}", campaign.getId(), e);
        }
    }

    private List<RecipientRow> fetchBatch(Connection connection, String rolePassword,
                                          FilterQuery filterQuery, int offset) {
        return externalRecipientQueryService.resolve(
                connection, rolePassword, filterQuery, offset, BATCH_SIZE);
    }

    private void failCampaign(Campaign campaign) {
        campaign.setStatus(CampaignStatus.FAILED);
        campaignRepository.save(campaign);
    }
}
