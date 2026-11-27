package com.broadcastmail.worker.config;

import com.broadcastmail.common.sentry.SentryEmailScrubber;
import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SentryConfig {

    @Bean
    SentryOptions.BeforeSendCallback sentryBeforeSend() {
        return (event, hint) -> SentryEmailScrubber.scrub(event);
    }
}
