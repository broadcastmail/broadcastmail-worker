package com.broadcastmail.worker.unsubscribe;

import com.broadcastmail.worker.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnsubscribeTokenService {
    private final AppProperties appProperties;

    private static final int MIN_SECRET_BYTES = 32;

    /** Fails startup instead of every send: HMAC-SHA256 needs a 32-byte key, and an unset env var stays as "${...}". */
    @PostConstruct
    void requireUsableSecret() {
        String secret = appProperties.unsubscribeSecret();
        if (secret == null || secret.contains("${") || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("UNSUBSCRIBE_SECRET must be set to at least " + MIN_SECRET_BYTES + " bytes");
        }
    }

    public String generateUnsubscribeUrl(UUID campaignRecipientId) {
            String token = Jwts.builder()
                    .subject(campaignRecipientId.toString())
                    .expiration(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                    .signWith(getKey())
                    .compact();
        return appProperties.frontend().url() + "/unsubscribe?token=" + token;
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(appProperties.unsubscribeSecret().getBytes());
    }
}
