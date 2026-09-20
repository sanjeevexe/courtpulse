package com.courtpulse.messaging.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class GameEventEnvelopeCodec {
    private final ObjectMapper objectMapper;

    public GameEventEnvelopeCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(GameEventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode queue envelope", exception);
        }
    }

    public GameEventEnvelope decode(String body) {
        try {
            return objectMapper.readValue(body, GameEventEnvelope.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidQueueMessageException("Invalid game-event envelope", exception);
        }
    }
}
