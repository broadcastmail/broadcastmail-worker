package com.broadcastmail.worker.account;

import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.worker.common.exceptions.PlanLimitExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountPlanServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Mock
    private CampaignRecipientRepository campaignRecipientRepository;
    @InjectMocks
    private AccountPlanService accountPlanService;

    @Test
    void shouldPassWhenUnderLimit() {
        // Given
        when(campaignRecipientRepository.countUniqueRecipientsSince(eq(ACCOUNT_ID), any()))
                .thenReturn(499L);

        // When / Then
        assertThatNoException().isThrownBy(() -> accountPlanService.checkRecipientLimit(ACCOUNT_ID));
    }

    @Test
    void shouldPassWhenAtLimit() {
        // Given
        when(campaignRecipientRepository.countUniqueRecipientsSince(eq(ACCOUNT_ID), any()))
                .thenReturn(500L);

        // When / Then
        assertThatNoException().isThrownBy(() -> accountPlanService.checkRecipientLimit(ACCOUNT_ID));
    }

    @Test
    void shouldThrowWhenOverLimit() {
        // Given
        when(campaignRecipientRepository.countUniqueRecipientsSince(eq(ACCOUNT_ID), any()))
                .thenReturn(501L);

        // When / Then
        assertThatThrownBy(() -> accountPlanService.checkRecipientLimit(ACCOUNT_ID))
                .isInstanceOf(PlanLimitExceededException.class);
    }
}