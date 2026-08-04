package com.broadcastmail.worker.config;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.encryption")
public record EncryptionProperties(
        String key
) {
}

