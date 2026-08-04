package com.broadcastmail.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String unsubscribeSecret,
        String frontendUrl
) {}
