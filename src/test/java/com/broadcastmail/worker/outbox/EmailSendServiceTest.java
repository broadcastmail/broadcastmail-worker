package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.worker.common.exceptions.EmailProviderNotFoundException;
import com.broadcastmail.worker.resend.ResendClient;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.broadcastmail.worker.unsubscribe.UnsubscribeTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailSendServiceTest {
    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private EmailProviderRepository emailProviderRepository;
    @Mock
    private ResendClient resendClient;

    @Mock
    private UnsubscribeTokenService unsubscribeTokenService;


    private EmailSendService emailSendService;
    private Campaign campaign;
    private EmailProvider emailProvider;
    private CampaignRecipient recipient;

    private static final String TEST_KEY = "12345678901234567890123456789012";
    private final EncryptionProperties encryptionProperties = new EncryptionProperties(TEST_KEY);

    @BeforeEach
    void setUp() {
        emailSendService = new EmailSendService(
                campaignRepository,
                emailProviderRepository,
                resendClient,
                encryptionProperties,
                unsubscribeTokenService
        );

        campaign = Campaign.builder()
                .id(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .subject("Hello subscribers")
                .bodyHtml("<p>Welcome</p>")
                .build();

        emailProvider = EmailProvider.builder()
                .accountId(campaign.getAccountId())
                .encryptedApiKey(SecurityUtil.encrypt("re_testkey123", TEST_KEY))
                .fromAddress("hello@example.com")
                .build();

        recipient = CampaignRecipient.builder()
                .id(UUID.randomUUID())
                .campaignId(campaign.getId())
                .email("user@example.com")
                .idempotencyKey("idempotency-key-123")
                .status(RecipientStatus.QUEUED)
                .build();

    }

    private static final String TEST_UNSUBSCRIBE_URL = "https://app.example.com/unsubscribe?token=test-token";

    private void stubHappyPath() {
        when(campaignRepository.findById(campaign.getId()))
                .thenReturn(Optional.of(campaign));
        when(emailProviderRepository.findByAccountId(campaign.getAccountId()))
                .thenReturn(Optional.of(emailProvider));
        when(unsubscribeTokenService.generateUnsubscribeUrl(recipient.getId()))
                .thenReturn(TEST_UNSUBSCRIBE_URL);
    }

    @Test
    void shouldSendEmailWithCampaignSubjectAndBodyToRecipientAddress() {
        // Given
        stubHappyPath();
        when(resendClient.sendEmail(anyString(), any(ResendSendRequest.class), anyString()))
                .thenReturn("msg-123");

        // When
        emailSendService.sendEmail(recipient);

        // Then
        verify(resendClient).sendEmail(
                anyString(),
                argThat(req -> req.subject().equals("Hello subscribers")
                        && req.html().equals("<p>Welcome</p><br><br><a href=\"" + TEST_UNSUBSCRIBE_URL + "\">Unsubscribe</a>")
                        && req.to().contains("user@example.com")),
                eq("idempotency-key-123")
        );
    }

    @Test
    void shouldGenerateUnsubscribeUrlForRecipientAndAppendItToBody() {
        // Given
        stubHappyPath();
        when(resendClient.sendEmail(anyString(), any(ResendSendRequest.class), anyString()))
                .thenReturn("msg-123");

        // When
        emailSendService.sendEmail(recipient);

        // Then
        verify(unsubscribeTokenService).generateUnsubscribeUrl(recipient.getId());
        verify(resendClient).sendEmail(
                anyString(),
                argThat(req -> req.unsubscribeUrl().equals(TEST_UNSUBSCRIBE_URL)
                        && req.html().contains("<a href=\"" + TEST_UNSUBSCRIBE_URL + "\">Unsubscribe</a>")),
                anyString()
        );
    }

    @Test
    void shouldPassIdempotencyKeyToResend() {
        // Given
        stubHappyPath();
        when(resendClient.sendEmail(anyString(), any(ResendSendRequest.class), anyString()))
                .thenReturn("msg-123");

        // When
        emailSendService.sendEmail(recipient);

        // Then
        verify(resendClient).sendEmail(anyString(), any(), eq("idempotency-key-123"));
    }

    @Test
    void shouldThrowWhenCampaignNotFound() {
        // Given
        when(campaignRepository.findById(campaign.getId()))
                .thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> emailSendService.sendEmail(recipient))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void shouldThrowWhenEmailProviderNotFound() {
        // Given
        when(campaignRepository.findById(campaign.getId()))
                .thenReturn(Optional.of(campaign));
        when(emailProviderRepository.findByAccountId(campaign.getAccountId()))
                .thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> emailSendService.sendEmail(recipient))
                .isInstanceOf(EmailProviderNotFoundException.class);
    }
}