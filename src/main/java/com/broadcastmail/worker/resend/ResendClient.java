package com.broadcastmail.worker.resend;

import com.broadcastmail.worker.common.exceptions.EmailSendException;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
import com.broadcastmail.worker.common.exceptions.ResendThrottledException;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.batch.model.CreateBatchEmailsResponse;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class ResendClient {

    private final Cache<String, Resend> clientsByApiKey = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    // Resend allows 10 requests/second per team, so calls are paced per API key.
    private static final int REQUESTS_PER_SECOND = 10;
    private final Cache<String, RequestPacer> pacersByApiKey = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    public CreateBatchEmailsResponse sendEmailBatch(String apiKey, List<ResendSendRequest> requests) {
        List<CreateEmailOptions> emails = requests.stream()
                .map(r -> CreateEmailOptions.builder()
                        .from(r.from())
                        .to(r.to())
                        .subject(r.subject())
                        .html(r.html())
                        .addHeader("List-Unsubscribe", "<" + r.unsubscribeUrl() + ">")
                        .addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
                        .addHeader("Idempotency-Key", r.idempotencyKey())
                        .build())
                .toList();
        try {
            Resend resend = clientsByApiKey.get(apiKey, Resend::new);
            assert resend != null;
            pacersByApiKey.get(apiKey, _ -> new RequestPacer(REQUESTS_PER_SECOND)).acquire();
            return resend.batch().send(emails);
        } catch (ResendException e) {
            if (Integer.valueOf(429).equals(e.getStatusCode())) {
                // Only rate_limit_exceeded is the per-second limit; daily/monthly quota (or an unknown 429) waits for the quota.
                if ("rate_limit_exceeded".equals(e.getErrorName())) {
                    throw new ResendThrottledException("Resend per-second rate limit hit");
                }
                throw new ResendRateLimitException("Resend sending quota reached: " + e.getErrorName());
            }
            throw new EmailSendException("Failed to send email batch: " + e.getMessage());
        }
    }
}
