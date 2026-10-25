package com.broadcastmail.worker.resend;

import com.broadcastmail.worker.common.exceptions.EmailSendException;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
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
            return resend.batch().send(emails);
        } catch (ResendException e) {
            if (e.getStatusCode() == 429) {
                throw new ResendRateLimitException("Too many emails sent in a short period of time.");
            }
            throw new EmailSendException("Failed to send email batch: " + e.getMessage());
        }
    }
}
