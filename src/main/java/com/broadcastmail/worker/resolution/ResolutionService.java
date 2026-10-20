package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.account.Account;
import com.broadcastmail.common.account.AccountRepository;
import com.broadcastmail.common.campaign.AudienceMode;
import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.common.campaign.filter.CampaignFilter;
import com.broadcastmail.common.campaign.filter.CampaignFilterRepository;
import com.broadcastmail.common.campaign.filter.CampaignFilterSerializer;
import com.broadcastmail.common.campaign.filter.FilterQuery;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.PlanLimitExceededException;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionService {
    private static final int BATCH_SIZE = 100;


    private final ConnectionRepository connectionRepository;
    private final CampaignFilterRepository filterRepository;
    private final CampaignFilterSerializer filterSerializer;
    private final BatchPersistenceService batchPersistenceService;
    private final ExternalRecipientQueryService externalRecipientQueryService;
    private final CampaignRepository campaignRepository;
    private final EncryptionProperties encryptionProperties;
    private final AccountRepository accountRepository;
    private final CampaignRecipientRepository campaignRecipientRepository;

    public void resolve(Campaign campaign) {
        try {
            Connection connection = connectionRepository.findByAccountId(campaign.getAccountId())
                    .orElseThrow(() -> new RuntimeException("Connection not found for account: " + campaign.getAccountId()));

            Account account = accountRepository.findById(campaign.getAccountId())
                    .orElseThrow(() -> new RuntimeException("Account not found: " + campaign.getAccountId()));

            String rolePassword = SecurityUtil.decrypt(connection.getEncryptedCreds(), encryptionProperties.key());
            int historicalCount = (int) campaignRecipientRepository.countUniqueRecipientsSince(
                    account.getId(), OffsetDateTime.now(ZoneId.systemDefault()).minusDays(30));

            // Backward-compat: null audienceMode with filters → treat as FILTER.
            AudienceMode mode = campaign.getAudienceMode();
            if (mode == null) mode = AudienceMode.FILTER;

            int totalResolved = mode == AudienceMode.FILTER
                    ? resolveByFilters(campaign, connection, rolePassword, account, historicalCount)
                    : resolveByExplicitIds(campaign, connection, rolePassword, account, historicalCount, mode);

            campaign.setStatus(CampaignStatus.SENDING);
            campaign.setRecipientCount(totalResolved);
            campaignRepository.save(campaign);

        } catch (PlanLimitExceededException _) {
            failCampaign(campaign, "Recipient limit reached for your plan.");
            log.warn("Plan limit exceeded for campaign {}", campaign.getId());
        } catch (Exception e) {
            failCampaign(campaign, "Resolution failed: " + e.getMessage());
            log.error("Resolution failed for campaign {}", campaign.getId(), e);
        }
    }

    private int resolveByFilters(Campaign campaign, Connection connection, String rolePassword,
                                  Account account, int historicalCount) {
        List<CampaignFilter> filters = filterRepository.findByCampaignId(campaign.getId());
        FilterQuery filterQuery = filterSerializer.serialize(filters);
        int totalResolved = 0;
        int offset = 0;
        List<RecipientRow> batch = fetchBatch(connection, rolePassword, filterQuery, offset);
        while (!batch.isEmpty()) {
            batchPersistenceService.persistBatch(campaign.getId(), batch);
            account.getPlan().strategy().checkRecipientLimit((long) historicalCount + totalResolved, batch.size());
            totalResolved += batch.size();
            offset += BATCH_SIZE;
            batch = fetchBatch(connection, rolePassword, filterQuery, offset);
        }
        return totalResolved;
    }

    private int resolveByExplicitIds(Campaign campaign, Connection connection, String rolePassword,
                                      Account account, int historicalCount, AudienceMode mode) {
        Set<String> excluded = campaign.getExcludedIds() != null
                ? new HashSet<>(campaign.getExcludedIds()) : Set.of();

        if (mode == AudienceMode.MANUAL) {
            List<String> included = campaign.getIncludedIds() != null ? campaign.getIncludedIds() : List.of();
            List<String> effectiveIds = included.stream()
                    .filter(id -> !excluded.contains(id))
                    .toList();
            List<RecipientRow> candidates = externalRecipientQueryService.resolveByIds(connection, rolePassword, effectiveIds);
            int totalResolved = 0;
            for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
                List<RecipientRow> batch = candidates.subList(i, Math.min(i + BATCH_SIZE, candidates.size()));
                batchPersistenceService.persistBatch(campaign.getId(), batch);
                account.getPlan().strategy().checkRecipientLimit((long) historicalCount + totalResolved, batch.size());
                totalResolved += batch.size();
            }
            return totalResolved;
        }

        // ALL: cursor-paginated full-table scan, excluding excluded IDs per batch.
        FilterQuery emptyQuery = filterSerializer.serialize(List.of());
        int totalResolved = 0;
        int offset = 0;
        List<RecipientRow> rawBatch = fetchBatch(connection, rolePassword, emptyQuery, offset);
        while (!rawBatch.isEmpty()) {
            List<RecipientRow> batch = rawBatch.stream()
                    .filter(r -> !excluded.contains(r.userId()))
                    .toList();
            if (!batch.isEmpty()) {
                batchPersistenceService.persistBatch(campaign.getId(), batch);
                account.getPlan().strategy().checkRecipientLimit((long) historicalCount + totalResolved, batch.size());
                totalResolved += batch.size();
            }
            offset += BATCH_SIZE;
            rawBatch = fetchBatch(connection, rolePassword, emptyQuery, offset);
        }
        return totalResolved;
    }

    private List<RecipientRow> fetchBatch(Connection connection, String rolePassword,
                                          FilterQuery filterQuery, int offset) {
        return externalRecipientQueryService.resolve(
                connection, rolePassword, filterQuery, offset, BATCH_SIZE);
    }

    private void failCampaign(Campaign campaign, String reason) {
        campaign.setStatus(CampaignStatus.FAILED);
        campaign.setFailureReason(reason);
        campaignRepository.save(campaign);
    }
}
