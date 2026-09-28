package com.courtpulse.messaging.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.MessageRejectedException;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

/** SES behavior is exercised against LocalStack; no AWS account or credential is involved. */
@Testcontainers(disabledWithoutDocker = true)
class SesEmailSenderLocalStackTest {
    @Container
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.4.0")).withServices("ses");
    private static final String SENDER = "alerts@courtpulse.test";
    private static SesClient ses;

    @BeforeAll
    static void verifySender() {
        ses = SesClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        ses.verifyEmailIdentity(builder -> builder.emailAddress(SENDER));
    }

    @Test
    void sendsPlainTextFromTheVerifiedSenderAndReturnsTheProviderId() throws Exception {
        SesEmailSender sender = new SesEmailSender(ses, SENDER, null);

        String messageId = sender.send("fan@example.test", "CourtPulse: Ada\r\nLane reached 30",
                "Ada Lane reached 30 points\nGame: bdl-game-990001");

        assertNotNull(messageId);
        HttpResponse<String> sent = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(LOCALSTACK.getEndpoint() + "/_aws/ses?email=" + SENDER)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode messages = new ObjectMapper().readTree(sent.body()).path("messages");
        assertTrue(messages.size() >= 1, sent.body());
        JsonNode message = messages.get(messages.size() - 1);
        assertEquals(messageId, message.path("Id").asText());
        assertEquals("CourtPulse: Ada Lane reached 30", message.path("Subject").asText(),
                "header injection characters are removed from the subject");
        assertTrue(message.path("Destination").path("ToAddresses").toString().contains("fan@example.test"));
    }

    @Test
    void failuresMapToFixedRetryableAndPermanentCodes() {
        assertEquals("invalid_recipient", assertThrows(EmailSendException.class,
                () -> new SesEmailSender(ses, SENDER, null).send("not-an-address", "s", "b")).code());
        assertThrows(IllegalArgumentException.class, () -> new SesEmailSender(ses, "bad sender", null));

        EmailSendException unverified = assertThrows(EmailSendException.class,
                () -> new SesEmailSender(ses, "unverified@courtpulse.test", null).send("fan@example.test", "s", "b"));
        assertEquals("ses_message_rejected", unverified.code(), "LocalStack rejects an unverified sender");
        assertFalse(unverified.retryable());

        SesClient rejecting = mock(SesClient.class);
        when(rejecting.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(MessageRejectedException.builder().message("Email address is not verified").build());
        EmailSendException rejected = assertThrows(EmailSendException.class,
                () -> new SesEmailSender(rejecting, SENDER, null).send("fan@example.test", "s", "b"));
        assertEquals("ses_message_rejected", rejected.code());
        assertFalse(rejected.retryable());

        SesClient throttled = mock(SesClient.class);
        when(throttled.sendEmail(any(SendEmailRequest.class))).thenThrow(SesException.builder()
                .statusCode(400).awsErrorDetails(AwsErrorDetails.builder().errorCode("Throttling").build())
                .message("Maximum sending rate exceeded").build());
        EmailSendException slow = assertThrows(EmailSendException.class,
                () -> new SesEmailSender(throttled, SENDER, null).send("fan@example.test", "s", "b"));
        assertEquals("ses_throttled", slow.code());
        assertTrue(slow.retryable());

        SesClient offline = mock(SesClient.class);
        when(offline.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SdkClientException.create("connection reset"));
        EmailSendException unavailable = assertThrows(EmailSendException.class,
                () -> new SesEmailSender(offline, SENDER, null).send("fan@example.test", "s", "b"));
        assertEquals("ses_unavailable", unavailable.code());
        assertTrue(unavailable.retryable());
    }
}
