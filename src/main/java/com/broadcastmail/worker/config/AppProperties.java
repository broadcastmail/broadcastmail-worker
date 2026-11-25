package com.broadcastmail.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String unsubscribeSecret,
        Frontend frontend,
        Api api
) {
    public record Frontend(String url) {}

    public record Api(String publicUrl) {}
}
