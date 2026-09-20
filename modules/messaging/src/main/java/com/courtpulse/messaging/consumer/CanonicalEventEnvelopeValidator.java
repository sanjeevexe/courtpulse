package com.courtpulse.messaging.consumer;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.messaging.queue.GameEventEnvelope;
import com.courtpulse.messaging.queue.InvalidQueueMessageException;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;

/** Validates untrusted queue metadata against committed PostgreSQL records. */
public final class CanonicalEventEnvelopeValidator {
    private final JdbcGameProcessingRepository events;
    private final JdbcOutboxPublicationRepository outbox;

    public CanonicalEventEnvelopeValidator(
            JdbcGameProcessingRepository events,
            JdbcOutboxPublicationRepository outbox) {
        this.events = events;
        this.outbox = outbox;
    }

    public void validate(GameEventEnvelope envelope) {
        CanonicalEvent event;
        try {
            event = events.findEvent(envelope.eventId());
        } catch (IllegalArgumentException exception) {
            throw new InvalidQueueMessageException("Envelope refers to an unknown canonical event", exception);
        }
        boolean canonicalMatch = event.gameId().equals(envelope.gameId())
                && event.sequence() == envelope.sequence()
                && event.source().equals(envelope.source())
                && event.providerEventId().equals(envelope.providerEventId())
                && event.revision() == envelope.revision()
                && event.occurredAt().equals(envelope.occurredAt());
        if (!canonicalMatch) {
            throw new InvalidQueueMessageException(
                    "Envelope routing metadata does not match the canonical event");
        }
        if (!envelope.messageId().equals(envelope.outboxId().toString())
                || !outbox.matchesGameEventOutbox(
                        envelope.outboxId(),
                        envelope.deduplicationKey(),
                        envelope.eventId(),
                        envelope.gameId())) {
            throw new InvalidQueueMessageException(
                    "Envelope publication identity does not match the transactional outbox");
        }
    }
}
