package com.broadcastmail.worker.unsubscribe;

import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.unsubscribe.UnsubscribeClaims;
import com.broadcastmail.common.unsubscribe.UnsubscribeTokenCodec;
import com.broadcastmail.worker.unsubscribe.UnsubscribeTokenService.UnsubscribeLinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsubscribeTokenServiceTest {

    private static final String SECRET = "12345678901234567890123456789012";
    private static final String FRONTEND_URL = "https://broadcastmail.dev";
    private static final String API_URL = "https://api.broadcastmail.dev";

    private final UUID accountId = UUID.randomUUID();
    private final CampaignRecipient recipient = CampaignRecipient.builder()
            .id(UUID.randomUUID())
            .email("Jane.Doe@Example.com")
            .build();

    private static UnsubscribeTokenService serviceWith(String secret, String frontendUrl, String apiUrl) {
        return new UnsubscribeTokenService(secret, frontendUrl, apiUrl);
    }

    @Test
    void bodyLinkGoesToTheFrontendAndHeaderLinkToTheApi() {
        UnsubscribeLinks links = serviceWith(SECRET, FRONTEND_URL, API_URL).linksFor(accountId, recipient);

        assertThat(links.pageUrl()).startsWith(FRONTEND_URL + "/unsubscribe?token=");
        assertThat(links.oneClickUrl()).startsWith(API_URL + "/unsubscribe?token=");
        assertThat(tokenOf(links.pageUrl())).isEqualTo(tokenOf(links.oneClickUrl()));
    }

    @Test
    void tokenCarriesTheRecipientAccountAndEmailExactly() {
        UnsubscribeLinks links = serviceWith(SECRET, FRONTEND_URL, API_URL).linksFor(accountId, recipient);

        // The api reads it with the same codec and secret.
        assertThat(new UnsubscribeTokenCodec(SECRET).read(tokenOf(links.pageUrl())))
                .isEqualTo(new UnsubscribeClaims(recipient.getId(), accountId, "Jane.Doe@Example.com"));
    }

    @Test
    void tokenDoesNotExposeTheEmail() {
        UnsubscribeLinks links = serviceWith(SECRET, FRONTEND_URL, API_URL).linksFor(accountId, recipient);

        assertThat(links.pageUrl()).doesNotContainIgnoringCase("jane");
    }

    @Test
    void ignoresATrailingSlashOnTheConfiguredUrls() {
        UnsubscribeLinks links = serviceWith(SECRET, FRONTEND_URL + "/", API_URL + "/").linksFor(accountId, recipient);

        assertThat(links.pageUrl()).startsWith(FRONTEND_URL + "/unsubscribe?token=");
        assertThat(links.oneClickUrl()).startsWith(API_URL + "/unsubscribe?token=");
    }

    @ParameterizedTest
    @ValueSource(strings = {"${UNSUBSCRIBE_SECRET}", "too-short"})
    void refusesToStartWithAnUnsetOrShortSecret(String secret) {
        assertThatThrownBy(() -> serviceWith(secret, FRONTEND_URL, API_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSUBSCRIBE_SECRET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "${API_PUBLIC_URL}"})
    void refusesToStartWithoutAnApiUrl(String apiUrl) {
        assertThatThrownBy(() -> serviceWith(SECRET, FRONTEND_URL, apiUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_PUBLIC_URL");
    }

    private static String tokenOf(String url) {
        return url.substring(url.indexOf("token=") + 6);
    }
}
