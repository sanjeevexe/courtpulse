package com.courtpulse.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.courtpulse.persistence.DurableGameProcessor;
import com.courtpulse.persistence.FailureMode;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.RuleEngineMetrics;
import com.courtpulse.persistence.SimulatedProcessingFailureException;
import com.courtpulse.api.http.RuleApiDto;
import com.courtpulse.domain.alert.RuleType;
import com.courtpulse.api.rules.PersonalizedRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
    "logging.level.root=ERROR",
    "courtpulse.realtime.publication.enabled=false"
})
@AutoConfigureMockMvc
class PersonalizedRulesIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc http;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PersonalizedRuleService ruleService;

    private ApiTestData.SeededServices seeded;

    @BeforeEach
    void seed() {
        seeded = ApiTestData.seed(dataSource, false);
    }

    @Test
    void rulesRequireAuthenticationAndRejectUnknownFields() throws Exception {
        http.perform(get("/api/v1/me/rules")).andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .header("Idempotency-Key", "unknown-field")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"PLAYER_POINTS","gameId":"game_synthetic_001",
                                 "playerId":"player_ace","pointsThreshold":10,"expression":"unsafe"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(content().string(not(containsString("expression"))));
        http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .header("Idempotency-Key", "invalid-discriminator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SCRIPT","gameId":"game_synthetic_001","source":"unsafe"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(content().string(not(containsString("source"))));
    }

    @Test
    void creationIsIdempotentButConflictingReuseIsRejected() throws Exception {
        String body = playerRule(10);
        MvcResult created = http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .header("Idempotency-Key", "same-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("PLAYER_POINTS"))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .header("Idempotency-Key", "same-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        http.perform(patch("/api/v1/me/rules/" + id)
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"version\":1}"))
                .andExpect(status().isOk());
        http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .header("Idempotency-Key", "same-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .header("Idempotency-Key", "same-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(playerRule(11)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("rule_conflict"));
        assertEquals(1L, JdbcClient.create(dataSource).sql(
                        "SELECT count(*) FROM alert_rules WHERE owner_subject = 'user-a'")
                .query(Long.class).single());
    }

    @Test
    void ownerIsolationAndOptimisticUpdatesDoNotRevealForeignRules() throws Exception {
        UUIDResult created = create("user-a", "owner-a", playerRule(10));
        http.perform(get("/api/v1/me/rules/" + created.id())
                        .with(jwt().jwt(value -> value.subject("user-b"))))
                .andExpect(status().isNotFound());
        http.perform(patch("/api/v1/me/rules/" + created.id())
                        .with(jwt().jwt(value -> value.subject("user-b")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"version\":1}"))
                .andExpect(status().isNotFound());
        http.perform(delete("/api/v1/me/rules/" + created.id())
                        .with(jwt().jwt(value -> value.subject("user-b"))))
                .andExpect(status().isNotFound());

        http.perform(patch("/api/v1/me/rules/" + created.id())
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.version").value(2));
        http.perform(patch("/api/v1/me/rules/" + created.id())
                        .with(jwt().jwt(value -> value.subject("user-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"version\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void threeRuleTypesCreatePrivateAlertsWithoutPublicRealtimeLeakage() throws Exception {
        UUIDResult player = create("user-a", "player", playerRule(10));
        create("user-a", "close", """
                {"type":"CLOSE_GAME","gameId":"game_synthetic_001","maximumMargin":3,
                 "eligiblePeriod":4,"maximumClockMillisRemaining":720000}
                """);
        create("user-a", "run", """
                {"type":"SCORING_RUN","gameId":"game_synthetic_001","teamId":"team_home",
                 "pointsThreshold":5}
                """);

        JdbcClient jdbc = JdbcClient.create(dataSource);
        ObjectMapper persistenceJson = new ObjectMapper().findAndRegisterModules();
        JdbcGameProcessingRepository processing = seeded.processing();
        DurableGameProcessor processor = new DurableGameProcessor(
                processing,
                new com.courtpulse.persistence.JdbcOutboxRepository(jdbc, persistenceJson),
                new TransactionTemplate(transactionManager),
                new JdbcAlertRuleRepository(jdbc, persistenceJson),
                clock,
                RuleEngineMetrics.NONE);
        processing.listEventIds("game_synthetic_001").forEach(processor::processEvent);
        processing.listEventIds("game_synthetic_001").forEach(processor::processEvent);

        http.perform(get("/api/v1/me/alerts")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
        http.perform(get("/api/v1/me/alerts")
                        .with(jwt().jwt(value -> value.subject("user-b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        http.perform(get("/api/v1/games/game_synthetic_001/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(content().string(not(containsString(player.id().toString()))));

        assertEquals(1L, jdbc.sql("""
                        SELECT count(*) FROM outbox
                        WHERE destination = 'FUTURE_NOTIFICATIONS' AND event_type = 'ALERT_CREATED'
                        """).query(Long.class).single());
        assertEquals(4L, jdbc.sql("SELECT count(*) FROM alert_instances")
                .query(Long.class).single());
    }

    @Test
    void privateAlertAndRunAccumulatorRollbackAndSurviveCommitCrashRedelivery() throws Exception {
        create("failure-owner", "failure-player", playerRule(10));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ObjectMapper persistenceJson = new ObjectMapper().findAndRegisterModules();
        JdbcGameProcessingRepository processing = seeded.processing();
        DurableGameProcessor processor = new DurableGameProcessor(
                processing,
                new com.courtpulse.persistence.JdbcOutboxRepository(jdbc, persistenceJson),
                new TransactionTemplate(transactionManager),
                new JdbcAlertRuleRepository(jdbc, persistenceJson),
                clock,
                RuleEngineMetrics.NONE);
        var ids = processing.listEventIds("game_synthetic_001");
        ids.subList(0, 10).forEach(processor::processEvent);
        String beforeRun = jdbc.sql("""
                        SELECT coalesce(team_id, 'none') || '|' || points || '|' ||
                               coalesce(start_sequence::text, 'none')
                        FROM game_scoring_runs WHERE game_id = 'game_synthetic_001'
                        """).query(String.class).single();
        long beforeAlerts = jdbc.sql("SELECT count(*) FROM alert_instances")
                .query(Long.class).single();
        String milestoneEvent = ids.get(10);

        org.junit.jupiter.api.Assertions.assertThrows(
                SimulatedProcessingFailureException.class,
                () -> processor.processEvent(milestoneEvent, FailureMode.BEFORE_COMMIT));
        assertEquals(10L, jdbc.sql("""
                        SELECT state_version FROM game_checkpoints
                        WHERE game_id = 'game_synthetic_001'
                        """).query(Long.class).single());
        assertEquals(beforeAlerts, jdbc.sql("SELECT count(*) FROM alert_instances")
                .query(Long.class).single());
        assertEquals(beforeRun, jdbc.sql("""
                        SELECT coalesce(team_id, 'none') || '|' || points || '|' ||
                               coalesce(start_sequence::text, 'none')
                        FROM game_scoring_runs WHERE game_id = 'game_synthetic_001'
                        """).query(String.class).single());

        org.junit.jupiter.api.Assertions.assertThrows(
                SimulatedProcessingFailureException.class,
                () -> processor.processEvent(milestoneEvent, FailureMode.AFTER_COMMIT));
        long committedAlerts = jdbc.sql("SELECT count(*) FROM alert_instances")
                .query(Long.class).single();
        assertEquals(beforeAlerts + 2, committedAlerts);
        String committedRun = jdbc.sql("""
                        SELECT coalesce(team_id, 'none') || '|' || points || '|' ||
                               coalesce(start_sequence::text, 'none')
                        FROM game_scoring_runs WHERE game_id = 'game_synthetic_001'
                        """).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertFalse(processor.processEvent(milestoneEvent).accepted());
        assertEquals(committedAlerts, jdbc.sql("SELECT count(*) FROM alert_instances")
                .query(Long.class).single());
        assertEquals(committedRun, jdbc.sql("""
                        SELECT coalesce(team_id, 'none') || '|' || points || '|' ||
                               coalesce(start_sequence::text, 'none')
                        FROM game_scoring_runs WHERE game_id = 'game_synthetic_001'
                        """).query(String.class).single());
    }

    @Test
    void concurrentIdempotentCreationProducesOneOwnedRule() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        RuleApiDto.PlayerPointsRequest request = new RuleApiDto.PlayerPointsRequest(
                RuleType.PLAYER_POINTS, "game_synthetic_001", "player_ace", 10, true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return ruleService.create("concurrent-user", "one-logical-request", request);
            });
            var second = executor.submit(() -> {
                start.await();
                return ruleService.create("concurrent-user", "one-logical-request", request);
            });
            start.countDown();
            var results = java.util.List.of(first.get(), second.get());
            assertEquals(1L, results.stream().filter(PersonalizedRuleService.Creation::created).count());
        }
        assertEquals(1L, JdbcClient.create(dataSource).sql(
                        "SELECT count(*) FROM alert_rules WHERE owner_subject = 'concurrent-user'")
                .query(Long.class).single());
    }

    @Test
    void paginationIsStableAndQuotaIsEnforcedAtFiftyRules() throws Exception {
        for (int index = 1; index <= PersonalizedRuleService.MAX_RULES_PER_USER; index++) {
            create("quota-user", "quota-" + index, playerRule(10));
        }
        MvcResult first = http.perform(get("/api/v1/me/rules?limit=20")
                        .with(jwt().jwt(value -> value.subject("quota-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsString()).path("nextCursor").asText();
        http.perform(get("/api/v1/me/rules?limit=20&cursor="
                        + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8))
                        .with(jwt().jwt(value -> value.subject("quota-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20));
        http.perform(get("/api/v1/me/rules?limit=101")
                        .with(jwt().jwt(value -> value.subject("quota-user"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        http.perform(get("/api/v1/me/rules?cursor=not-opaque")
                        .with(jwt().jwt(value -> value.subject("quota-user"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
        http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("quota-user")))
                        .header("Idempotency-Key", "quota-overflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(playerRule(10)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rule_quota_exceeded"));
    }

    @Test
    void perGameQuotaBoundsCandidatesAcrossManyOwners() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                        INSERT INTO application_users(subject, created_at, last_seen_at)
                        SELECT 'cap-user-' || n, now(), now()
                        FROM generate_series(1, 20) n
                        """).update();
        jdbc.sql("""
                        INSERT INTO alert_rules (
                          id, owner_subject, game_id, rule_type, enabled,
                          player_id, points_threshold, client_request_id, request_fingerprint,
                          version, created_at, updated_at)
                        SELECT md5('cap-rule-' || n)::uuid,
                               'cap-user-' || (((n - 1) / 50) + 1),
                               'game_synthetic_001', 'PLAYER_POINTS', TRUE,
                               'player_ace', 10, 'cap-' || n, repeat('a', 64),
                               1, now(), now()
                        FROM generate_series(1, 1000) n
                        """).update();
        http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject("next-owner")))
                        .header("Idempotency-Key", "over-game-cap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(playerRule(10)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rule_quota_exceeded"));
        JdbcAlertRuleRepository repository = new JdbcAlertRuleRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        var matching = seeded.processing().listEventIds("game_synthetic_001").stream()
                .map(seeded.processing()::findEvent)
                .filter(event -> event.participantIds().contains("player_ace"))
                .findFirst().orElseThrow();
        assertEquals(1001, repository.relevantRules(matching).enabledRules().size());
    }

    @Test
    void databaseRejectsWhitespaceOnlyStoredTargets() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                        INSERT INTO application_users(subject, created_at, last_seen_at)
                        VALUES ('invalid-owner', now(), now())
                        """).update();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.sql("""
                                INSERT INTO alert_rules (
                                  id, owner_subject, game_id, rule_type, enabled,
                                  player_id, points_threshold, client_request_id, request_fingerprint,
                                  version, created_at, updated_at)
                                VALUES (
                                  gen_random_uuid(), 'invalid-owner', 'game_synthetic_001',
                                  'PLAYER_POINTS', TRUE, '   ', 10, 'invalid',
                                  repeat('a', 64), 1, now(), now())
                                """).update());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.sql("""
                                INSERT INTO alert_rules (
                                  id, owner_subject, game_id, rule_type, enabled,
                                  team_id, points_threshold, client_request_id, request_fingerprint,
                                  version, created_at, updated_at)
                                VALUES (
                                  gen_random_uuid(), 'invalid-owner', 'game_synthetic_001',
                                  'SCORING_RUN', TRUE, E'\t\t', 5, 'invalid-tabs',
                                  repeat('a', 64), 1, now(), now())
                                """).update());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.sql("""
                                INSERT INTO alert_rules (
                                  id, owner_subject, game_id, rule_type, enabled,
                                  player_id, points_threshold, version, created_at, updated_at)
                                VALUES (
                                  gen_random_uuid(), NULL, 'game_synthetic_001',
                                  'PLAYER_POINTS', TRUE, 'private-player', 10, 1, now(), now())
                                """).update());
    }

    @Test
    void disableAndDeleteWaitForAnInFlightSharedRuleLookup() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcAlertRuleRepository repository = new JdbcAlertRuleRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        var event = seeded.processing().listEventIds("game_synthetic_001").stream()
                .map(seeded.processing()::findEvent)
                .filter(candidate -> candidate.participantIds().contains("player_ace"))
                .findFirst().orElseThrow();

        for (boolean deleteAfterLookup : new boolean[] {false, true}) {
            UUIDResult created = create("lock-owner", "lock-" + deleteAfterLookup, playerRule(10));
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var lookup = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .execute(status -> {
                            assertTrue(repository.relevantRules(event).enabledRules().stream()
                                    .anyMatch(rule -> rule.ruleId().equals(created.id().toString())));
                            locked.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Timed out holding the rule lookup");
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                            return null;
                        }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var mutation = executor.submit(() -> jdbc.sql(deleteAfterLookup
                                ? "DELETE FROM alert_rules WHERE id = :id"
                                : "UPDATE alert_rules SET enabled = FALSE WHERE id = :id")
                        .param("id", created.id()).update());
                try {
                    org.junit.jupiter.api.Assertions.assertThrows(
                            TimeoutException.class, () -> mutation.get(200, TimeUnit.MILLISECONDS));
                } finally {
                    release.countDown();
                }
                lookup.get(5, TimeUnit.SECONDS);
                assertEquals(1, mutation.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void primaryAndOwnerHistoryQueriesUseTheirBoundedIndexes() throws Exception {
        create("plan-user", "plan-player", playerRule(10));
        create("plan-user", "plan-close", """
                {"type":"CLOSE_GAME","gameId":"game_synthetic_001","maximumMargin":3,
                 "eligiblePeriod":4,"maximumClockMillisRemaining":720000}
                """);
        create("plan-user", "plan-run", """
                {"type":"SCORING_RUN","gameId":"game_synthetic_001","teamId":"team_home",
                 "pointsThreshold":5}
                """);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ObjectMapper persistenceJson = new ObjectMapper().findAndRegisterModules();
        DurableGameProcessor processor = new DurableGameProcessor(
                seeded.processing(),
                new com.courtpulse.persistence.JdbcOutboxRepository(jdbc, persistenceJson),
                new TransactionTemplate(transactionManager),
                new JdbcAlertRuleRepository(jdbc, persistenceJson),
                clock,
                RuleEngineMetrics.NONE);
        seeded.processing().listEventIds("game_synthetic_001").forEach(processor::processEvent);

        String relevant = explain("""
                SELECT id FROM alert_rules
                WHERE enabled AND game_id = 'game_synthetic_001' AND (
                  (rule_type = 'CLOSE_GAME' AND eligible_period = 4
                    AND maximum_clock_millis_remaining >= 1000 AND maximum_margin >= 2)
                  OR (rule_type = 'PLAYER_POINTS' AND player_id = 'player_ace')
                  OR (rule_type = 'SCORING_RUN' AND team_id = 'team_home'))
                ORDER BY id
                """);
        assertTrue(relevant.contains("idx_alert_rules_game_player"), relevant);
        assertTrue(relevant.contains("idx_alert_rules_game_team"), relevant);
        assertTrue(relevant.contains("idx_alert_rules_game_close"), relevant);
        assertTrue(explain("""
                SELECT id FROM alert_rules WHERE owner_subject = 'plan-user'
                ORDER BY created_at DESC, id ASC LIMIT 21
                """).contains("idx_alert_rules_owner_order"));
        assertTrue(explain("""
                SELECT id FROM alert_instances WHERE owner_subject = 'plan-user'
                ORDER BY created_at DESC, id ASC LIMIT 21
                """).contains("idx_alert_instances_owner_order"));
    }

    private String explain(String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (ResultSet rows = statement.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    private UUIDResult create(String subject, String key, String body) throws Exception {
        MvcResult result = http.perform(post("/api/v1/me/rules")
                        .with(jwt().jwt(value -> value.subject(subject)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        return new UUIDResult(java.util.UUID.fromString(response.path("id").asText()));
    }

    private static String playerRule(int threshold) {
        return """
                {"type":"PLAYER_POINTS","gameId":"game_synthetic_001",
                 "playerId":"player_ace","pointsThreshold":%d}
                """.formatted(threshold);
    }

    private record UUIDResult(java.util.UUID id) {}
}
