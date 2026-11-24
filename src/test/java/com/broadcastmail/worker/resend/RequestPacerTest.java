package com.broadcastmail.worker.resend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPacerTest {

    @Test
    void spacesBackToBackCallsEvenlyAcrossTheSecond() {
        RequestPacer pacer = new RequestPacer(10);

        long first = pacer.reserve();
        long second = pacer.reserve();
        long eleventh = 0;
        for (int i = 3; i <= 11; i++) eleventh = pacer.reserve();

        assertThat(first).isLessThanOrEqualTo(0);
        assertThat(second).isBetween(90_000_000L, 100_000_000L);
        // the 11th call in a burst must wait about a full second
        assertThat(eleventh).isBetween(990_000_000L, 1_000_000_000L);
    }
}
