package com.courtpulse.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

public final class OpaqueCursorCodec {
    private static final int VERSION = 1;
    private final ObjectMapper objectMapper;

    public OpaqueCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public String encode(String type, String scope, List<String> keys) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(
                    new CursorPayload(VERSION, require(type), scope, List.copyOf(keys)));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode pagination cursor", exception);
        }
    }

    public List<String> decode(String cursor, String expectedType, String expectedScope, int keyCount) {
        if (cursor == null || cursor.isBlank() || cursor.length() > 2_048) {
            throw new InvalidCursorException("Cursor is malformed or empty");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII));
            CursorPayload payload = objectMapper.readValue(decoded, CursorPayload.class);
            if (payload.version() != VERSION
                    || !expectedType.equals(payload.type())
                    || !Objects.equals(expectedScope, payload.scope())
                    || payload.keys() == null
                    || payload.keys().size() != keyCount
                    || payload.keys().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new InvalidCursorException("Cursor is incompatible with this resource");
            }
            return List.copyOf(payload.keys());
        } catch (InvalidCursorException exception) {
            throw exception;
        } catch (IllegalArgumentException | IOException exception) {
            throw new InvalidCursorException("Cursor is malformed", exception);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cursor type must not be blank");
        }
        return value;
    }

    private record CursorPayload(int version, String type, String scope, List<String> keys) {}
}
