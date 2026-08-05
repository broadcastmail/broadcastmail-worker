package com.broadcastmail.worker.unsubscribe;

import com.broadcastmail.worker.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class UnsubscribeTokenServiceTest {
    private UnsubscribeTokenService unsubscribeTokenService;

    private static final String TEST_SECRET = "12345678901234567890123456789012";
    private static final String TEST_FRONTEND_URL = "http://localhost:8080";
    @BeforeEach
    void setUp() {
        unsubscribeTokenService = new UnsubscribeTokenService(
                new AppProperties(TEST_SECRET, new AppProperties.Frontend(TEST_FRONTEND_URL))
        );
    }

    @Test
    void shouldGenerateValidTokenWithRecipientId() {
        // Given
        UUID recipientId = UUID.randomUUID();

        // When
        String url = unsubscribeTokenService.generateUnsubscribeUrl(recipientId);

        // Then
        assertThat(url).startsWith(TEST_FRONTEND_URL + "/unsubscribe?token=");
        String token = url.substring(url.indexOf("token=") + 6);
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(recipientId.toString());
    }

    @Test
    void shouldExpireAfter30Days() {
        // Given
        UUID recipientId = UUID.randomUUID();

        // When
        String url = unsubscribeTokenService.generateUnsubscribeUrl(recipientId);
        String token = url.substring(url.indexOf("token=") + 6);
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // Then
        assertThat(claims.getExpiration())
                .isAfter(Date.from(Instant.now().plus(29, ChronoUnit.DAYS)))
                .isBefore(Date.from(Instant.now().plus(31, ChronoUnit.DAYS)));
    }

}