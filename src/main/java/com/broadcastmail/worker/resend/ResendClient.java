package com.broadcastmail.worker.resend;

import com.broadcastmail.worker.common.exceptions.EmailSendException;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ResendClient {

    private final Cache<String, Resend> clientsByApiKey = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    public String sendEmail(String apiKey, ResendSendRequest request, String idempotencyKey) {
        try {
            Resend resend = clientsByApiKey.get(apiKey, Resend::new);
            CreateEmailOptions params = CreateEmailOptions.builder()
                    .from(request.from())
                    .to(request.to())
                    .subject(request.subject())
                    .html(request.html())
                    .addHeader("List-Unsubscribe", "<" + request.unsubscribeUrl() + ">")
                    .addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
                    .addHeader("Idempotency-Key", idempotencyKey)
                    .build();
            assert resend != null;
            CreateEmailResponse response = resend.emails().send(params);
            return response.getId();
        } catch (ResendException e) {
            if(e.getStatusCode()==429) // Code 429 - Too many requests
            {
                throw new ResendRateLimitException("Too many email emails sent in a short period of time.");
            }
            throw new EmailSendException("Failed to send email: " + e.getMessage());
        }
    }
}
