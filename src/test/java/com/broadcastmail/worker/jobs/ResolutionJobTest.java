package com.broadcastmail.worker.jobs;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.CampaignStatus;
import com.broadcastmail.worker.resolution.ResolutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResolutionJobTest {

    @Mock private CampaignRepository campaignRepository;
    @Mock private ResolutionService resolutionService;

    @InjectMocks private ResolutionJob resolutionJob;

    @Test
    void triggerResolvesRESOLVINGCampaigns() {
        Campaign campaign = Campaign.builder().id(UUID.randomUUID()).build();
        when(campaignRepository.findAllByStatus(CampaignStatus.RESOLVING)).thenReturn(List.of(campaign));

        resolutionJob.trigger();

        verify(resolutionService, timeout(2000)).resolve(campaign);
    }

    @Test
    void triggerDuringARunChecksAgainAfterwardsWithoutRunningInParallel() throws Exception {
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        when(campaignRepository.findAllByStatus(CampaignStatus.RESOLVING)).thenAnswer(inv -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            if (runs.incrementAndGet() == 1) {
                firstRunStarted.countDown();
                releaseFirstRun.await(2, TimeUnit.SECONDS);
            }
            concurrent.decrementAndGet();
            return List.of();
        });

        resolutionJob.trigger();
        assertThat(firstRunStarted.await(2, TimeUnit.SECONDS)).isTrue();
        resolutionJob.trigger(); // arrives mid-run, e.g. a second campaign confirmed
        resolutionJob.trigger();
        releaseFirstRun.countDown();

        verify(campaignRepository, timeout(2000).times(2)).findAllByStatus(CampaignStatus.RESOLVING);
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }
}
