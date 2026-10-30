package com.broadcastmail.worker.outbox;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipient;
import com.broadcastmail.common.campaign.recipient.RecipientStatus;
import com.broadcastmail.common.emailprovider.EmailProvider;
import com.broadcastmail.common.emailprovider.EmailProviderRepository;
import com.broadcastmail.common.suppressions.Suppression;
import com.broadcastmail.common.suppressions.SuppressionsRepository;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.worker.common.exceptions.EmailProviderNotFoundException;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.broadcastmail.worker.resend.ResendClient;
import com.broadcastmail.worker.resend.ResendUsageService;
import com.broadcastmail.worker.resend.dto.ResendSendRequest;
import com.broadcastmail.worker.unsubscribe.UnsubscribeTokenService;
import com.resend.services.batch.model.BatchEmail;
import com.resend.services.batch.model.CreateBatchEmailsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailSendServiceTest {
    @Mock private CampaignRepository campaignRepository;
    @Mock private EmailProviderRepository emailProviderRepository;
    @Mock private ResendClient resendClient;
    @Mock private ResendUsageService resendUsageService;
    @Mock private UnsubscribeTokenService unsubscribeTokenService;
    @Mock private SuppressionsRepository suppressionsRepository;

    private EmailSendService emailSendService;
    private Campaign campaign;
    private EmailProvider emailProvider;
    private CampaignRecipient recipient;

    private static final String TEST_KEY = "12345678901234567890123456789012";
    private static final String TEST_UNSUBSCRIBE_URL = "https://app.example.com/unsubscribe?token=test-token";
    private final EncryptionProperties encryptionProperties = new EncryptionProperties(TEST_KEY);

    @BeforeEach
    void setUp() {
        emailSendService = new EmailSendService(
                campaignRepository,
                emailProviderRepository,
                resendClient,
                resendUsageService,
                encryptionProperties,
                unsubscribeTokenService,
                suppressionsRepository
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
                .externalUserId("ext-user-1")
                .email("user@example.com")
                .idempotencyKey("idempotency-key-123")
                .status(RecipientStatus.QUEUED)
                .build();
    }

    private void stubCampaignAndProvider() {
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(emailProviderRepository.findByAccountId(campaign.getAccountId())).thenReturn(Optional.of(emailProvider));
    }

    private void stubHappyPath() {
        stubCampaignAndProvider();
        when(suppressionsRepository.findByAccountIdAndEmailIn(any(), any())).thenReturn(List.of());
        when(unsubscribeTokenService.generateUnsubscribeUrl(recipient.getId())).thenReturn(TEST_UNSUBSCRIBE_URL);
        when(resendClient.sendEmailBatch(anyString(), any()))
                .thenReturn(new CreateBatchEmailsResponse(List.of(new BatchEmail("msg-123")), List.of()));
    }

    private Suppression suppressionFor(String email) {
        return Suppression.builder()
                .accountId(campaign.getAccountId())
                .email(email)
                .reason("BOUNCE")
                .build();
    }

    private CampaignRecipient recipientWithEmail(String email) {
        return CampaignRecipient.builder()
                .id(UUID.randomUUID())
                .campaignId(campaign.getId())
                .externalUserId("ext-" + email)
                .email(email)
                .idempotencyKey("key-" + email)
                .status(RecipientStatus.QUEUED)
                .build();
    }

    @Test
    void shouldSendBatchWithCampaignSubjectAndBodyToRecipientAddress() {
        // Given
        stubHappyPath();

        // When
        emailSendService.sendEmailBatch(List.of(recipient), Map.of());

        // Then
        verify(resendClient).sendEmailBatch(anyString(), argThat((List<ResendSendRequest> reqs) -> {
            ResendSendRequest req = reqs.get(0);
            return req.to().contains("user@example.com")
                    && req.subject().equals("Hello subscribers")
                    && req.html().contains("<p>Welcome</p>")
                    && req.html().contains("<a href=\"" + TEST_UNSUBSCRIBE_URL + "\">Unsubscribe</a>")
                    && req.idempotencyKey().equals("idempotency-key-123");
        }));
    }

    @Test
    void shouldAppendUnsubscribeUrlToBody() {
        // Given
        stubHappyPath();

        // When
        emailSendService.sendEmailBatch(List.of(recipient), Map.of());

        // Then
        verify(unsubscribeTokenService).generateUnsubscribeUrl(recipient.getId());
        verify(resendClient).sendEmailBatch(anyString(), argThat((List<ResendSendRequest> reqs) ->
                reqs.get(0).html().contains("<a href=\"" + TEST_UNSUBSCRIBE_URL + "\">Unsubscribe</a>")));
    }

    @Test
    void shouldReturnMessageIdFromBatchResponse() {
        // Given
        stubHappyPath();

        // When
        List<BatchSendOutcome> outcomes = emailSendService.sendEmailBatch(List.of(recipient), Map.of());

        // Then
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).messageId()).isEqualTo("msg-123");
        assertThat(outcomes.get(0).succeeded()).isTrue();
    }

    @Test
    void shouldReturnSuppressedOutcomeAndSkipSendForSuppressedRecipient() {
        // Given
        stubCampaignAndProvider();
        when(suppressionsRepository.findByAccountIdAndEmailIn(any(), any()))
                .thenReturn(List.of(suppressionFor(recipient.getEmail())));

        // When
        List<BatchSendOutcome> outcomes = emailSendService.sendEmailBatch(List.of(recipient), Map.of());

        // Then
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.getFirst().suppressed()).isTrue();
        assertThat(outcomes.getFirst().succeeded()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(resendClient);
    }

    @Test
    void shouldOnlySendToNonSuppressedRecipientsInMixedBatch() {
        // Given
        CampaignRecipient suppressedRecipient = recipientWithEmail("suppressed@example.com");
        stubCampaignAndProvider();
        when(suppressionsRepository.findByAccountIdAndEmailIn(any(), any()))
                .thenReturn(List.of(suppressionFor("suppressed@example.com")));
        when(unsubscribeTokenService.generateUnsubscribeUrl(recipient.getId())).thenReturn(TEST_UNSUBSCRIBE_URL);
        when(resendClient.sendEmailBatch(anyString(), any()))
                .thenReturn(new CreateBatchEmailsResponse(List.of(new BatchEmail("msg-123")), List.of()));

        // When
        List<BatchSendOutcome> outcomes = emailSendService.sendEmailBatch(
                List.of(recipient, suppressedRecipient), Map.of());

        // Then
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).succeeded()).isTrue();
        assertThat(outcomes.get(1).suppressed()).isTrue();
    }

    @Test
    void shouldThrowWhenCampaignNotFound() {
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.empty());

        List<CampaignRecipient> recipients = List.of(recipient);
        assertThatThrownBy(() -> emailSendService.sendEmailBatch(recipients, Map.of()))
                .isInstanceOf(CampaignNotFoundException.class);
    }

    @Test
    void shouldThrowWhenEmailProviderNotFound() {
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(emailProviderRepository.findByAccountId(campaign.getAccountId())).thenReturn(Optional.empty());

        List<CampaignRecipient> recipients = List.of(recipient);
        assertThatThrownBy(() -> emailSendService.sendEmailBatch(recipients, Map.of()))
                .isInstanceOf(EmailProviderNotFoundException.class);
    }
}
