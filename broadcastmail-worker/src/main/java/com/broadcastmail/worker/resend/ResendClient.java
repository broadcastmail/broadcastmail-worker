package com.broadcastmail.worker.resend;

import com.broadcastmail.worker.common.exceptions.EmailSendException;
import com.broadcastmail.worker.common.exceptions.ResendRateLimitException;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.springframework.stereotype.Component;

@Component
public class ResendClient {
    public String sendEmail(String apiKey, ResendSendRequest request, String idempotencyKey) {
        try {
            Resend resend = new Resend(apiKey);
            CreateEmailOptions params = CreateEmailOptions.builder()
                    .from(request.from())
                    .to(request.to())
                    .subject(request.subject())
                    .html(request.html())
                    .addHeader("Idempotency-Key", idempotencyKey)
                    .build();
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
