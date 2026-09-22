package com.courtpulse.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.providers.fixture.FixtureGame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "logging.level.root=ERROR",
            "courtpulse.api.live-freshness-window=2m",
            "courtpulse.realtime.publication.enabled=false"
        })
class CourtPulseApiIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private ApiTestData.SeededServices services;

    @BeforeEach
    void seedFinalGame() {
        services = ApiTestData.seed(dataSource, true);
    }

    @Test
    void gameListIsDeterministicAndStatusFilterWorks() throws Exception {
        Instant now = ApiTestData.CLOCK.instant();
        services.fixtures().ensureGame("synthetic", new FixtureGame("game_a", "home", "away"), now);
        services.fixtures().ensureGame("synthetic", new FixtureGame("game_z", "home", "away"), now);

        JsonNode all = body(get("/api/v1/games?limit=10"));
        assertEquals(List.of("game_a", "game_synthetic_001", "game_z"),
                values(all.path("items"), "gameId"));

        HttpResponse<String> finalsResponse = get("/api/v1/games?status=FINAL");
        assertEquals(200, finalsResponse.statusCode(), finalsResponse.body());
        JsonNode finals = body(finalsResponse);
        assertEquals(1, finals.path("items").size());
        assertEquals("game_synthetic_001", finals.path("items").get(0).path("gameId").asText());
    }

    @Test
    void gameCursorHandlesTimestampTiesAndConcurrentInsertWithoutRepeats() throws Exception {
        Instant now = ApiTestData.CLOCK.instant();
        services.fixtures().ensureGame("synthetic", new FixtureGame("game_a", "home", "away"), now);
        services.fixtures().ensureGame("synthetic", new FixtureGame("game_z", "home", "away"), now);

        JsonNode first = body(get("/api/v1/games?limit=2"));
        assertEquals(List.of("game_a", "game_synthetic_001"), values(first.path("items"), "gameId"));
        String cursor = first.path("nextCursor").asText();

        services.fixtures().ensureGame(
                "synthetic", new FixtureGame("game_new", "home", "away"), now.plusSeconds(1));
        JsonNode second = body(get("/api/v1/games?limit=2&cursor=" + encode(cursor)));
        assertEquals(List.of("game_z"), values(second.path("items"), "gameId"));
    }

    @Test
    void snapshotReturnsFinalStateAndConditionalGetHasNoBody() throws Exception {
        HttpResponse<String> first = get("/api/v1/games/game_synthetic_001");
        JsonNode snapshot = body(first);
        String etag = first.headers().firstValue("ETag").orElseThrow();

        assertEquals(200, first.statusCode());
        assertEquals(18, snapshot.path("homeScore").asInt());
        assertEquals(14, snapshot.path("awayScore").asInt());
        assertEquals(13, snapshot.path("playerPoints").path("player_ace").asInt());
        assertEquals(20, snapshot.path("stateVersion").asLong());
        assertEquals("06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca",
                snapshot.path("stateChecksum").asText());
        assertEquals("FINAL", snapshot.path("dataStatus").asText());

        HttpResponse<String> unchanged = request("GET", "/api/v1/games/game_synthetic_001",
                "If-None-Match", etag);
        assertEquals(304, unchanged.statusCode());
        assertTrue(unchanged.body().isEmpty());
        assertEquals(etag, unchanged.headers().firstValue("ETag").orElseThrow());

        HttpResponse<String> weak = request("GET", "/api/v1/games/game_synthetic_001",
                "If-None-Match", "W/" + etag);
        assertEquals(304, weak.statusCode());
        assertTrue(weak.body().isEmpty());

        HttpResponse<String> multiple = request("GET", "/api/v1/games/game_synthetic_001",
                "If-None-Match", "\"stale-validator\", " + etag);
        assertEquals(304, multiple.statusCode());
        assertTrue(multiple.body().isEmpty());

        HttpResponse<String> stale = request("GET", "/api/v1/games/game_synthetic_001",
                "If-None-Match", "\"stale-validator\"");
        assertEquals(200, stale.statusCode());
        assertEquals(etag, stale.headers().firstValue("ETag").orElseThrow());
    }

    @Test
    void applyingEventChangesCheckpointAndEtag() throws Exception {
        services = ApiTestData.seed(dataSource, false);
        HttpResponse<String> before = get("/api/v1/games/game_synthetic_001");
        String oldEtag = before.headers().firstValue("ETag").orElseThrow();
        assertEquals(0, body(before).path("stateVersion").asLong());

        services.processor().processEvent(services.fixture().events().getFirst().eventId());
        HttpResponse<String> after = request("GET", "/api/v1/games/game_synthetic_001",
                "If-None-Match", oldEtag);

        assertEquals(200, after.statusCode());
        assertEquals(1, body(after).path("stateVersion").asLong());
        assertNotEquals(oldEtag, after.headers().firstValue("ETag").orElseThrow());
    }

    @Test
    void eventPaginationHasNoGapsDuplicatesOrRawPayloads() throws Exception {
        String cursor = null;
        List<Long> sequences = new ArrayList<>();
        Set<String> eventIds = new HashSet<>();
        do {
            String path = "/api/v1/games/game_synthetic_001/events?limit=7"
                    + (cursor == null ? "" : "&cursor=" + encode(cursor));
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains("rawPayload"));
            assertFalse(response.body().contains("contentHash"));
            JsonNode page = body(response);
            for (JsonNode event : page.path("items")) {
                sequences.add(event.path("sequence").asLong());
                assertTrue(eventIds.add(event.path("eventId").asText()));
                assertTrue(event.has("scoreAfter"));
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText(null);
        } while (cursor != null);

        assertEquals(20, sequences.size());
        assertEquals(20, eventIds.size());
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList(), sequences);
    }

    @Test
    void eventCursorUsesRevisionAndEventIdToResolveSequenceTies() throws Exception {
        services.jdbc().sql("""
                        INSERT INTO raw_provider_payloads (
                            id, source, game_id, provider_event_id, revision, content_hash, payload,
                            first_ingested_at, last_observed_at, observation_count)
                        VALUES (
                            '00000000-0000-0000-0000-000000000099', 'synthetic',
                            'game_synthetic_001', 'provider-event-20', 2,
                            '9999999999999999999999999999999999999999999999999999999999999999',
                            '{}'::jsonb, :now, :now, 1)
                        """)
                .param("now", ApiTestData.CLOCK.instant().atOffset(java.time.ZoneOffset.UTC))
                .update();
        services.jdbc().sql("""
                        INSERT INTO canonical_events (
                            event_id, schema_version, source, game_id, provider_event_id,
                            sequence_number, revision, event_type, period, clock_millis_remaining,
                            occurred_at, team_id, participant_ids, home_score, away_score, points,
                            raw_payload_id, canonical_payload, created_at)
                        SELECT 'event-sequence-20-revision-2', schema_version, source, game_id,
                               'provider-event-20', sequence_number, 2, event_type, period,
                               clock_millis_remaining, occurred_at, team_id, participant_ids,
                               home_score, away_score, points,
                               '00000000-0000-0000-0000-000000000099', canonical_payload, :now
                        FROM canonical_events
                        WHERE game_id = 'game_synthetic_001' AND sequence_number = 20
                        """)
                .param("now", ApiTestData.CLOCK.instant().atOffset(java.time.ZoneOffset.UTC))
                .update();

        String cursor = null;
        List<Integer> revisions = new ArrayList<>();
        do {
            String path = "/api/v1/games/game_synthetic_001/events?afterSequence=19&limit=1"
                    + (cursor == null ? "" : "&cursor=" + encode(cursor));
            if (cursor != null) {
                path = "/api/v1/games/game_synthetic_001/events?limit=1&cursor=" + encode(cursor);
            }
            JsonNode page = body(get(path));
            page.path("items").forEach(event -> revisions.add(event.path("revision").asInt()));
            cursor = page.path("nextCursor").asText(null);
        } while (cursor != null);

        assertEquals(List.of(1, 2), revisions);
    }

    @Test
    void eventAfterSequenceAndAlertHistoryAreBoundedAndOrdered() throws Exception {
        HttpResponse<String> eventsResponse =
                get("/api/v1/games/game_synthetic_001/events?afterSequence=17&limit=10");
        assertEquals(200, eventsResponse.statusCode(), eventsResponse.body());
        JsonNode events = body(eventsResponse);
        assertEquals(List.of(18L, 19L, 20L), longValues(events.path("items"), "sequence"));

        JsonNode alerts = body(get("/api/v1/games/game_synthetic_001/alerts?limit=10"));
        assertEquals(1, alerts.path("items").size());
        assertEquals("milestone-player-ace-10", alerts.path("items").get(0).path("ruleId").asText());
    }

    @Test
    void processingHealthRequiresAnOperationalIdentity() throws Exception {
        HttpResponse<String> response = get("/api/v1/operations/processing");
        JsonNode problem = body(response);

        assertEquals(401, response.statusCode());
        assertEquals("authentication_required", problem.path("code").asText());
        assertFalse(response.body().contains("last_error"));
        assertFalse(response.body().contains("password"));
    }

    @Test
    void invalidInputsAndUnknownGamesReturnSafeProblemDetails() throws Exception {
        assertProblem(get("/api/v1/games?limit=0"), 400, "invalid_parameter");
        assertProblem(get("/api/v1/games?limit=101"), 400, "invalid_parameter");
        assertProblem(get("/api/v1/games?cursor=not-a-cursor"), 400, "invalid_cursor");
        assertProblem(get("/api/v1/games/missing-game"), 404, "game_not_found");
        assertProblem(get("/api/v1/games/" + encode("x' OR '1'='1")), 404, "game_not_found");
        assertProblem(request("POST", "/api/v1/games"), 405, "method_not_allowed");
        assertProblem(request("GET", "/api/v1/games", "Accept", "application/xml"),
                406, "not_acceptable");
    }

    @Test
    void correlationIdsAreReturnedAndUnsafeValuesAreNormalized() throws Exception {
        HttpResponse<String> accepted = request("GET", "/api/v1/games", "X-Correlation-ID", "client-123");
        assertEquals("client-123", accepted.headers().firstValue("X-Correlation-ID").orElseThrow());

        HttpResponse<String> normalized = request("GET", "/api/v1/games", "X-Correlation-ID", "x".repeat(100));
        String generated = normalized.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertNotEquals("bad value\n", generated);
        assertTrue(generated.length() <= 64);
    }

    @Test
    void livenessReadinessInfoAndMetricsAreExposedSafely() throws Exception {
        assertEquals("UP", body(get("/actuator/health/liveness")).path("status").asText());
        assertEquals("UP", body(get("/actuator/health/readiness")).path("status").asText());
        assertEquals(401, get("/actuator/info").statusCode());
        assertEquals(401, get("/actuator/metrics").statusCode());
        assertEquals(401, get("/actuator/env").statusCode());
    }

    @Test
    void checkedContractMatchesRuntimeOperationsParametersResponsesAndPublicSchemas() throws Exception {
        JsonNode runtime = body(get("/v3/api-docs"));
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode contract = yaml.readTree(
                Path.of("..", "..", "contracts", "openapi", "courtpulse-v1.yaml")
                        .normalize()
                        .toFile());
        Set<String> expectedPaths = fieldNames(contract.path("paths"));
        Set<String> runtimePaths = fieldNames(runtime.path("paths"));

        assertEquals(expectedPaths, runtimePaths);
        for (String path : expectedPaths) {
            assertEquals(httpMethods(contract.path("paths").path(path)),
                    httpMethods(runtime.path("paths").path(path)));
            for (String method : httpMethods(contract.path("paths").path(path))) {
                JsonNode expectedOperation = contract.path("paths").path(path).path(method);
                JsonNode actualOperation = runtime.path("paths").path(path).path(method);
                assertEquals(parameters(contract, expectedOperation), parameters(runtime, actualOperation),
                        path + " " + method + " parameters");
                assertEquals(responses(contract, expectedOperation), responses(runtime, actualOperation),
                        path + " " + method + " responses");
            }
        }

        for (String schema : List.of(
                "GamePage", "GameSummary", "GameSnapshot", "RecentEvent", "EventPage",
                "Event", "Score", "AlertPage", "Alert", "Processing", "Me",
                "FollowedGamePage", "FollowedGame", "AuthenticationConfiguration", "Problem",
                "RulePage", "PlayerPointsRule", "CloseGameRule", "ScoringRunRule",
                "OwnedAlertPage", "OwnedAlert", "UpdateAlertRule", "RuleOperations")) {
            assertEquals(
                    publicSchema(contract, schema),
                    publicSchema(runtime, schema),
                    schema + " public schema");
        }

        Set<String> expectedDataStatuses = enumValues(
                contract.path("components").path("schemas").path("DataStatus"));
        assertEquals(Set.of("SCHEDULED", "LIVE", "FINAL", "STALE", "PROCESSING_BLOCKED"),
                expectedDataStatuses);
        assertEquals(expectedDataStatuses, enumValues(runtime.path("components").path("schemas")
                .path("GameSummary").path("properties").path("dataStatus")));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path);
    }

    private HttpResponse<String> request(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int index = 0; index < headers.length; index += 2) {
            builder.header(headers[index], headers[index + 1]);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json"));
        JsonNode problem = body(response);
        assertEquals(status, problem.path("status").asInt());
        assertEquals(code, problem.path("code").asText(), response.body());
        assertTrue(problem.hasNonNull("correlationId"));
        assertFalse(response.body().contains("Exception"));
        assertFalse(response.body().contains("SELECT"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<String> values(JsonNode values, String field) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.path(field).asText()));
        return result;
    }

    private static List<Long> longValues(JsonNode values, String field) {
        List<Long> result = new ArrayList<>();
        values.forEach(value -> result.add(value.path(field).asLong()));
        return result;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static Set<String> httpMethods(JsonNode path) {
        Set<String> methods = fieldNames(path);
        methods.retainAll(Set.of("get", "post", "put", "patch", "delete"));
        return methods;
    }

    private static Set<ParameterContract> parameters(JsonNode root, JsonNode operation) {
        Set<ParameterContract> result = new HashSet<>();
        operation.path("parameters").forEach(value -> {
            JsonNode parameter = resolve(root, value);
            JsonNode schema = resolve(root, parameter.path("schema"));
            result.add(new ParameterContract(
                    parameter.path("name").asText(),
                    parameter.path("in").asText(),
                    parameter.path("required").asBoolean(false),
                    scalarSchema(schema)));
        });
        return result;
    }

    private static Map<String, ResponseContract> responses(JsonNode root, JsonNode operation) {
        Map<String, ResponseContract> result = new TreeMap<>();
        operation.path("responses").properties().forEach(entry -> {
            JsonNode response = resolve(root, entry.getValue());
            result.put(entry.getKey(), new ResponseContract(
                    fieldNames(response.path("headers")),
                    fieldNames(response.path("content"))));
        });
        return result;
    }

    private static PublicSchema publicSchema(JsonNode root, String name) {
        JsonNode schema = resolve(root, root.path("components").path("schemas").path(name));
        Map<String, ScalarSchema> properties = new TreeMap<>();
        Set<String> required = new TreeSet<>();
        collectPublicSchema(root, schema, required, properties, new HashSet<>());
        return new PublicSchema(required, properties);
    }

    private static void collectPublicSchema(
            JsonNode root,
            JsonNode unresolved,
            Set<String> required,
            Map<String, ScalarSchema> properties,
            Set<String> visitedRefs) {
        if (unresolved.has("$ref")) {
            String ref = unresolved.path("$ref").asText();
            if (!visitedRefs.add(ref)) {
                return;
            }
        }
        JsonNode schema = resolve(root, unresolved);
        required.addAll(valuesSet(schema.path("required")));
        schema.path("properties").properties().forEach(entry ->
                properties.put(entry.getKey(), scalarSchema(resolve(root, entry.getValue()))));
        schema.path("allOf").forEach(component ->
                collectPublicSchema(root, component, required, properties, visitedRefs));
    }

    private static ScalarSchema scalarSchema(JsonNode schema) {
        Set<String> types = new TreeSet<>();
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            type.forEach(value -> types.add(value.asText()));
        } else if (type.isTextual()) {
            types.add(type.asText());
        } else if (schema.has("properties") || schema.has("additionalProperties")) {
            types.add("object");
        }
        if (schema.path("nullable").asBoolean(false)) {
            types.add("null");
        }
        return new ScalarSchema(
                types,
                textOrNull(schema, "format"),
                enumValues(schema),
                nestedType(schema.path("items")),
                nestedType(schema.path("additionalProperties")),
                textOrNull(schema, "minimum"),
                textOrNull(schema, "maximum"),
                textOrNull(schema, "default"),
                schema.has("maxLength") ? schema.path("maxLength").asInt() : null);
    }

    private static Set<String> enumValues(JsonNode schema) {
        return valuesSet(schema.path("enum"));
    }

    private static Set<String> valuesSet(JsonNode values) {
        Set<String> result = new TreeSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static String nestedType(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull() || schema.isBoolean()) {
            return schema != null && schema.asBoolean(false) ? "any" : null;
        }
        if (schema.has("$ref")) {
            return schema.path("$ref").asText().replace("#/components/schemas/", "ref:");
        }
        if (schema.path("type").isTextual()) {
            String format = textOrNull(schema, "format");
            return schema.path("type").asText() + (format == null ? "" : ":" + format);
        }
        return null;
    }

    private static JsonNode resolve(JsonNode root, JsonNode node) {
        JsonNode resolved = node;
        Set<String> visited = new HashSet<>();
        while (resolved != null && resolved.has("$ref")) {
            String ref = resolved.path("$ref").asText();
            if (!ref.startsWith("#/") || !visited.add(ref)) {
                throw new IllegalArgumentException("Unsupported or cyclic OpenAPI reference: " + ref);
            }
            resolved = root.at(ref.substring(1));
        }
        return resolved;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private record ParameterContract(
            String name, String location, boolean required, ScalarSchema schema) {}

    private record ResponseContract(Set<String> headers, Set<String> contentTypes) {}

    private record PublicSchema(Set<String> required, Map<String, ScalarSchema> properties) {}

    private record ScalarSchema(
            Set<String> types,
            String format,
            Set<String> enumValues,
            String itemType,
            String additionalPropertyType,
            String minimum,
            String maximum,
            String defaultValue,
            Integer maxLength) {}
}
