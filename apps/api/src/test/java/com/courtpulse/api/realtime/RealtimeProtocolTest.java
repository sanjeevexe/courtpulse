package com.courtpulse.api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RealtimeProtocolTest {
    private final RealtimeProtocol protocol = new RealtimeProtocol(
            JsonMapper.builder().addModule(new JavaTimeModule()).build());

    @Test
    void decodesVersionedSubscription() {
        RealtimeSubscription subscription = protocol.decodeSubscription("""
                {"schemaVersion":1,"messageType":"SUBSCRIBE","gameId":"game-1","lastStateVersion":7}
                """);
        assertEquals("game-1", subscription.gameId());
        assertEquals(7L, subscription.lastStateVersion());
    }

    @Test
    void rejectsUnsupportedVersionAndInvalidSubscription() {
        RealtimeProtocolException version = assertThrows(
                RealtimeProtocolException.class,
                () -> protocol.decodeSubscription(
                        "{\"schemaVersion\":2,\"messageType\":\"SUBSCRIBE\",\"gameId\":\"game-1\"}"));
        assertEquals("unsupported_version", version.code());

        RealtimeProtocolException invalid = assertThrows(
                RealtimeProtocolException.class,
                () -> protocol.decodeSubscription(
                        "{\"schemaVersion\":1,\"messageType\":\"SUBSCRIBE\",\"gameId\":\"../bad\"}"));
        assertEquals("invalid_game_id", invalid.code());
    }

    @Test
    void rejectsMalformedJsonAndUnexpectedFields() {
        assertEquals("invalid_json", assertThrows(
                RealtimeProtocolException.class,
                () -> protocol.decodeSubscription("{bad-json")).code());
        assertEquals("invalid_message", assertThrows(
                RealtimeProtocolException.class,
                () -> protocol.decodeSubscription(
                        "{\"schemaVersion\":1,\"messageType\":\"SUBSCRIBE\",\"gameId\":\"game-1\",\"token\":\"secret\"}"))
                .code());
    }

    @Test
    void serializesPublicMessageWithoutInternalDetails() throws Exception {
        String json = protocol.encode(new RealtimeServerMessage(
                1, "GAME_STATE_UPDATED", "00000000-0000-0000-0000-000000000001",
                "game-1", 8L, Instant.parse("2026-09-21T12:00:00Z"), "trace-1",
                "event-8", null, null, null));
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(8, mapper.readTree(json).path("stateVersion").asInt());
        assertEquals("2026-09-21T12:00:00Z", mapper.readTree(json).path("emittedAt").textValue(),
                "emittedAt is a date-time string, as the AsyncAPI contract declares");
        assertFalse(json.toLowerCase().contains("sql"));
        assertFalse(json.toLowerCase().contains("exception"));
    }

    @Test
    void parsedAsyncApiContractMatchesTheImplementedProtocol() throws Exception {
        Path contract = Path.of("..", "..", "contracts", "asyncapi", "courtpulse-realtime-v1.yaml");
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(contract.toFile());

        assertEquals("3.0.0", root.path("asyncapi").asText());
        assertEquals("1.0.0", root.at("/info/version").asText());
        assertEquals("ws", root.at("/servers/local/protocol").asText());
        assertEquals("/ws/v1/games", root.at("/servers/local/pathname").asText());
        assertEquals("/ws/v1/games", root.at("/channels/gameHints/address").asText());
        assertEquals("receive", root.at("/operations/subscribeToGame/action").asText());
        assertEquals("send", root.at("/operations/receiveGameHints/action").asText());

        assertEquals("SUBSCRIBE", root.at("/components/schemas/Subscribe/properties/messageType/const").asText());
        assertEquals(1, root.at("/components/schemas/Subscribe/properties/schemaVersion/const").asInt());
        assertTrue(required(root.at("/components/schemas/Subscribe"), "schemaVersion", "messageType", "gameId"));

        JsonNode server = root.at("/components/schemas/ServerMessage");
        assertTrue(required(server, "schemaVersion", "messageType", "messageId", "gameId", "emittedAt", "correlationId"));
        assertEquals("uuid", server.at("/properties/messageId/format").asText());
        assertEquals("date-time", server.at("/properties/emittedAt/format").asText());
        assertEquals(0, server.at("/properties/stateVersion/minimum").asInt());
        assertEquals(200, server.at("/properties/gameId/maxLength").asInt());
        assertEquals("^[A-Za-z0-9._:-]+$", server.at("/properties/gameId/pattern").asText());

        String[] schemas = {
                "SubscriptionAcknowledged", "GameStateUpdated", "AlertCreated", "ResyncRequired", "Problem"
        };
        String[] constants = {
                "SUBSCRIPTION_ACKNOWLEDGED", "GAME_STATE_UPDATED", "ALERT_CREATED", "RESYNC_REQUIRED", "PROBLEM"
        };
        for (int index = 0; index < schemas.length; index++) {
            assertEquals(constants[index], root.at("/components/schemas/" + schemas[index]
                    + "/allOf/1/properties/messageType/const").asText(), schemas[index]);
        }
        assertTrue(required(root.at("/components/schemas/GameStateUpdated/allOf/1"), "stateVersion", "eventId"));
        assertTrue(required(root.at("/components/schemas/AlertCreated/allOf/1"), "stateVersion", "triggerKey"));
        assertEquals("version_gap", root.at(
                "/components/schemas/ResyncRequired/allOf/1/properties/code/const").asText());
    }

    private static boolean required(JsonNode schema, String... names) {
        JsonNode required = schema.path("required");
        for (String name : names) {
            boolean found = false;
            for (JsonNode value : required) {
                found |= name.equals(value.asText());
            }
            if (!found) return false;
        }
        return true;
    }
}
