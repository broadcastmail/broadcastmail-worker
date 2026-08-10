package com.broadcastmail.worker.account;

import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.worker.common.exceptions.PlanLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountPlanService {

    private final CampaignRecipientRepository campaignRecipientRepository;

    public void checkRecipientLimit(UUID accountId) {
        long uniqueThisPeriod = campaignRecipientRepository.countUniqueRecipientsSince(
                accountId, OffsetDateTime.now(ZoneId.systemDefault()).minusDays(30)
        );

        if (uniqueThisPeriod > 500) {
            throw new PlanLimitExceededException();
        }
    }
}
