package com.broadcastmail.worker.unsubscribe;

import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.unsubscribe.UnsubscribeClaims;
import com.broadcastmail.common.unsubscribe.UnsubscribeTokenCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UnsubscribeTokenService {

    /**
     * @param pageUrl     the link in the email body: the frontend confirm page, so scanners that open it don't unsubscribe anyone
     * @param oneClickUrl the List-Unsubscribe header target: the API, which mailbox providers POST to (RFC 8058)
     */
    public record UnsubscribeLinks(String pageUrl, String oneClickUrl) {}

    private final UnsubscribeTokenCodec codec;
    private final String frontendUrl;
    private final String apiUrl;

    /** Fails startup instead of every send when UNSUBSCRIBE_SECRET or API_PUBLIC_URL is unset. */
    public UnsubscribeTokenService(
            @Value("${app.unsubscribe-secret}") String secret,
            @Value("${app.frontend.url}") String frontendUrl,
            @Value("${app.api.public-url}") String apiUrl) {
        this.codec = new UnsubscribeTokenCodec(secret);
        this.frontendUrl = requireUrl("FRONTEND_URL", frontendUrl);
        this.apiUrl = requireUrl("API_PUBLIC_URL", apiUrl);
    }

    public UnsubscribeLinks linksFor(UUID accountId, CampaignRecipient recipient) {
        String token = codec.issue(new UnsubscribeClaims(recipient.getId(), accountId, recipient.getEmail()));
        return new UnsubscribeLinks(
                frontendUrl + "/unsubscribe?token=" + token,
                apiUrl + "/unsubscribe?token=" + token);
    }

    private static String requireUrl(String name, String url) {
        if (url == null || url.isBlank() || url.contains("${")) {
            throw new IllegalStateException(name + " must be set");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
