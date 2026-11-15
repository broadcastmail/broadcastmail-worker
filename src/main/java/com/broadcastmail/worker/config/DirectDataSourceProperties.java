package com.broadcastmail.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.datasource.direct")
public record DirectDataSourceProperties(
        String url,
        String username,
        String password,
        String driverClassName,
        Hikari hikari
) {
    public record Hikari(int maximumPoolSize, String poolName) {}
}
