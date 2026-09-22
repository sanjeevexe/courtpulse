package com.courtpulse.messaging.delivery;

/** Provider-neutral boundary. A returned ID is only a provider-supplied acceptance identifier. */
public interface EmailSender {
    String send(String address, String subject, String body);
}
