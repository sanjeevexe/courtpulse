package com.courtpulse.api.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Set;
import java.util.regex.Pattern;

public final class RealtimeProtocol {
    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    private static final Set<String> SUBSCRIPTION_FIELDS =
            Set.of("schemaVersion", "messageType", "gameId", "lastStateVersion");

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    public RealtimeProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
        // The contract declares emittedAt as an RFC 3339 string, not Jackson's default epoch number.
        this.writer = mapper.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public RealtimeSubscription decodeSubscription(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) {
                throw new RealtimeProtocolException("invalid_message", "Expected a JSON object");
            }
            root.fieldNames().forEachRemaining(field -> {
                if (!SUBSCRIPTION_FIELDS.contains(field)) {
                    throw new RealtimeProtocolException("invalid_message", "Unexpected subscription field");
                }
            });
            int version = requiredInteger(root, "schemaVersion");
            if (version != RealtimeServerMessage.SCHEMA_VERSION) {
                throw new RealtimeProtocolException("unsupported_version", "Unsupported protocol version");
            }
            if (!"SUBSCRIBE".equals(requiredText(root, "messageType"))) {
                throw new RealtimeProtocolException("invalid_message", "Expected SUBSCRIBE message");
            }
            String gameId = requiredText(root, "gameId");
            if (!GAME_ID.matcher(gameId).matches()) {
                throw new RealtimeProtocolException("invalid_game_id", "Game ID is malformed");
            }
            Long lastStateVersion = null;
            if (root.has("lastStateVersion")) {
                if (!root.get("lastStateVersion").canConvertToLong()
                        || root.get("lastStateVersion").longValue() < 0) {
                    throw new RealtimeProtocolException(
                            "invalid_state_version", "Last state version must be non-negative");
                }
                lastStateVersion = root.get("lastStateVersion").longValue();
            }
            return new RealtimeSubscription(version, "SUBSCRIBE", gameId, lastStateVersion);
        } catch (RealtimeProtocolException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new RealtimeProtocolException("invalid_json", "Message is not valid JSON");
        }
    }

    public String encode(RealtimeServerMessage message) {
        try {
            return writer.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode realtime message", exception);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new RealtimeProtocolException("invalid_message", "Required string field is invalid");
        }
        return value.textValue();
    }

    private static int requiredInteger(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new RealtimeProtocolException("invalid_message", "Required integer field is invalid");
        }
        return value.intValue();
    }
}
