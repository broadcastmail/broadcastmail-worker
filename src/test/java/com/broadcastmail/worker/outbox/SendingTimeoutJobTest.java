package com.broadcastmail.worker.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SendingTimeoutJobTest {

    @Mock private SendingTimeoutService sendingTimeoutService;
    @InjectMocks private SendingTimeoutJob job;

    @Test
    void failsEveryTimedOutCampaign() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(sendingTimeoutService.findTimedOut()).thenReturn(List.of(a, b));

        job.failTimedOutCampaigns();

        verify(sendingTimeoutService).failUnsent(a);
        verify(sendingTimeoutService).failUnsent(b);
    }

    @Test
    void oneFailingCampaignDoesNotStopTheRest() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(sendingTimeoutService.findTimedOut()).thenReturn(List.of(broken, healthy));
        doThrow(new IllegalStateException("boom")).when(sendingTimeoutService).failUnsent(broken);

        job.failTimedOutCampaigns();

        verify(sendingTimeoutService).failUnsent(healthy);
    }
}
