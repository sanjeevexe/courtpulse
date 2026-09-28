package com.courtpulse.messaging.delivery;

import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.AccountSendingPausedException;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.ConfigurationSetDoesNotExistException;
import software.amazon.awssdk.services.ses.model.ConfigurationSetSendingPausedException;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.MessageRejectedException;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

/**
 * Amazon SES sender for deployed environments (the SES {@code SendEmail} API, IAM action
 * {@code ses:SendEmail}). Credentials come from the task role and only the configured, verified
 * sender is used. Failures map to fixed codes: throttling and service or network trouble retry
 * with the existing bounded backoff, while rejected recipients, unverified senders, missing
 * configuration, and bad requests are permanent. A timeout after SES accepted a message can still
 * produce a duplicate email; delivery is at least once by design (ADR 0009).
 */
public final class SesEmailSender implements EmailSender {
    private static final String ADDRESS = "^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$";
    private static final Set<String> THROTTLING = Set.of("Throttling", "ThrottlingException", "TooManyRequestsException");

    private final SesClient client;
    private final String fromAddress;
    private final String configurationSet;

    public SesEmailSender(SesClient client, String fromAddress, String configurationSet) {
        this.client = Objects.requireNonNull(client, "client is required");
        if (fromAddress == null || !fromAddress.matches(ADDRESS) || fromAddress.length() > 254) {
            throw new IllegalArgumentException("COURTPULSE_EMAIL_FROM must be a verified sender address");
        }
        this.fromAddress = fromAddress;
        this.configurationSet = configurationSet == null || configurationSet.isBlank() ? null : configurationSet;
    }

    @Override
    public String send(String address, String subject, String body) {
        if (address == null || address.length() > 254 || !address.matches(ADDRESS)) {
            throw new EmailSendException("invalid_recipient", false);
        }
        SendEmailRequest request = SendEmailRequest.builder()
                .source(fromAddress)
                .destination(Destination.builder().toAddresses(address).build())
                .message(Message.builder()
                        .subject(text(safeLine(subject)))
                        .body(Body.builder().text(text(body.replace("\r", ""))).build())
                        .build())
                .configurationSetName(configurationSet)
                .build();
        try {
            return client.sendEmail(request).messageId();
        } catch (MessageRejectedException exception) {
            throw new EmailSendException("ses_message_rejected", false);
        } catch (MailFromDomainNotVerifiedException exception) {
            throw new EmailSendException("ses_sender_not_verified", false);
        } catch (ConfigurationSetDoesNotExistException exception) {
            throw new EmailSendException("ses_configuration_missing", false);
        } catch (AccountSendingPausedException | ConfigurationSetSendingPausedException exception) {
            throw new EmailSendException("ses_sending_paused", true);
        } catch (SesException exception) {
            String code = exception.awsErrorDetails() == null ? null : exception.awsErrorDetails().errorCode();
            if (THROTTLING.contains(code)) {
                throw new EmailSendException("ses_throttled", true);
            }
            throw exception.statusCode() >= 500
                    ? new EmailSendException("ses_service_error", true)
                    : new EmailSendException("ses_bad_request", false);
        } catch (SdkClientException exception) {
            throw new EmailSendException("ses_unavailable", true);
        }
    }

    private static Content text(String value) {
        return Content.builder().data(value).charset("UTF-8").build();
    }

    private static String safeLine(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    }
}
