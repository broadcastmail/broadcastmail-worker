package com.broadcastmail.worker.unsubscribe;

import com.broadcastmail.worker.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnsubscribeTokenService {
    private final AppProperties appProperties;

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
