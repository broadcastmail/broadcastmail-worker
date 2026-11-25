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
import com.broadcastmail.common.usage.RecipientUsageRepository;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.RecipientSource;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.common.account.plan.PlanLimitExceededException;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.SupabaseConnectionException;
import com.broadcastmail.worker.outbox.CampaignStatusNotifier;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    private final RecipientUsageRepository recipientUsageRepository;
    private final CampaignStatusNotifier statusNotifier;

    public void resolve(Campaign campaign) {
        try {
            Connection connection = connectionRepository.findByAccountId(campaign.getAccountId())
                    .orElseThrow(() -> new RuntimeException("Connection not found for account: " + campaign.getAccountId()));

            Account account = accountRepository.findById(campaign.getAccountId())
                    .orElseThrow(() -> new RuntimeException("Account not found: " + campaign.getAccountId()));

            String rolePassword = SecurityUtil.decrypt(connection.getEncryptedCreds(), encryptionProperties.key());
            // Addresses emailed this period already used up quota; only new ones count against the plan limit.
            Set<String> usedThisPeriod = new HashSet<>(recipientUsageRepository.findEmailsSince(
                    account.getId(), OffsetDateTime.now(ZoneId.systemDefault()).minusDays(30)));

            Exclusions exclusions = exclusions(campaign);
            int totalResolved = campaign.getAudienceMode() == AudienceMode.MANUAL
                    ? resolveIncluded(campaign, connection, rolePassword, account, usedThisPeriod, exclusions)
                    : resolveByFilters(campaign, connection, rolePassword, account, usedThisPeriod, exclusions);

            campaign.setStatus(CampaignStatus.SENDING);
            campaign.setRecipientCount(totalResolved);
            campaignRepository.save(campaign);
            statusNotifier.notify(campaign.getId(), campaign.getAccountId());

        } catch (PlanLimitExceededException _) {
            failCampaign(campaign, "Recipient limit reached for your plan.");
            statusNotifier.notify(campaign.getId(), campaign.getAccountId());
            log.warn("Plan limit exceeded for campaign {}", campaign.getId());
        } catch (SupabaseConnectionException e) {
            failCampaign(campaign, "Could not connect to your database: " + e.getMessage());
            statusNotifier.notify(campaign.getId(), campaign.getAccountId());
            log.warn("Supabase unreachable for campaign {}: {}", campaign.getId(), e.getMessage());
        } catch (Exception e) {
            failCampaign(campaign, "Resolution failed: " + e.getMessage());
            statusNotifier.notify(campaign.getId(), campaign.getAccountId());
            log.warn("Resolution failed for campaign {}: {}", campaign.getId(), e.getMessage());
        }
    }

    // Unticked rows and the excluded campaign's past recipients; the API's recipient count applies the same rules.
    private record Exclusions(Set<String> ids, Set<String> emails) {
        boolean keeps(RecipientRow row) {
            return !ids.contains(row.userId()) && !emails.contains(row.email());
        }
    }

    private Exclusions exclusions(Campaign campaign) {
        Set<String> ids = campaign.getExcludedIds() != null ? new HashSet<>(campaign.getExcludedIds()) : Set.of();
        Set<String> emails = campaign.getExcludedCampaignId() != null
                ? new HashSet<>(campaignRecipientRepository.findDistinctEmailsByCampaignId(campaign.getExcludedCampaignId()))
                : Set.of();
        return new Exclusions(ids, emails);
    }

    // "all" and the legacy "filter" mode both mean: everyone matching the saved filters.
    private int resolveByFilters(Campaign campaign, Connection connection, String rolePassword,
                                  Account account, Set<String> usedThisPeriod, Exclusions exclusions) {
        List<CampaignFilter> filters = filterRepository.findByCampaignId(campaign.getId());
        String rootLogic = "OR".equalsIgnoreCase(campaign.getFilterRootLogic()) ? "OR" : "AND";
        FilterQuery filterQuery = filterSerializer.serialize(filters, rootLogic, RecipientSource.of(connection).filterAliases());
        int totalResolved = 0;
        int offset = 0;
        List<RecipientRow> rawBatch = fetchBatch(connection, rolePassword, filterQuery, offset);
        while (!rawBatch.isEmpty()) {
            List<RecipientRow> batch = rawBatch.stream().filter(exclusions::keeps).toList();
            totalResolved = persist(campaign, account, usedThisPeriod, totalResolved, batch);
            offset += BATCH_SIZE;
            rawBatch = fetchBatch(connection, rolePassword, filterQuery, offset);
        }
        return totalResolved;
    }

    private int resolveIncluded(Campaign campaign, Connection connection, String rolePassword,
                                Account account, Set<String> usedThisPeriod, Exclusions exclusions) {
        List<String> included = campaign.getIncludedIds() != null ? campaign.getIncludedIds() : List.of();
        List<RecipientRow> candidates = externalRecipientQueryService.resolveByIds(connection, rolePassword, included)
                .stream().filter(exclusions::keeps).toList();
        int totalResolved = 0;
        for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
            totalResolved = persist(campaign, account, usedThisPeriod, totalResolved,
                    candidates.subList(i, Math.min(i + BATCH_SIZE, candidates.size())));
        }
        return totalResolved;
    }

    private int persist(Campaign campaign, Account account, Set<String> usedThisPeriod, int totalResolved,
                        List<RecipientRow> batch) {
        if (batch.isEmpty()) return totalResolved;
        batchPersistenceService.persistBatch(campaign.getId(), batch);
        int used = usedThisPeriod.size();
        batch.forEach(row -> usedThisPeriod.add(row.email()));
        account.getPlan().strategy().checkRecipientLimit(used, usedThisPeriod.size() - used);
        return totalResolved + batch.size();
    }

    private List<RecipientRow> fetchBatch(Connection connection, String rolePassword,
                                          FilterQuery filterQuery, int offset) {
        return externalRecipientQueryService.resolve(
                connection, rolePassword, filterQuery, offset, BATCH_SIZE);
    }

    /** For a campaign that has sat in RESOLVING too long, e.g. the worker kept dying mid-run. */
    public void failTimedOut(Campaign campaign, Duration limit) {
        failCampaign(campaign, "Resolution timed out after " + limit.toMinutes() + " minutes.");
        statusNotifier.notify(campaign.getId(), campaign.getAccountId());
        log.warn("Resolution timed out for campaign {}", campaign.getId());
    }

    private void failCampaign(Campaign campaign, String reason) {
        campaign.setStatus(CampaignStatus.FAILED);
        campaign.setFailureReason(reason);
        campaignRepository.save(campaign);
    }
}
