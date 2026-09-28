package com.courtpulse.persistence;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.alert.RuleEvaluationFacts;
import com.courtpulse.domain.alert.ScoringRunFacts;
import com.courtpulse.domain.alert.ScoringRunRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameReducer;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.providers.fixture.LoadedSourceEvent;
import com.courtpulse.observability.TraceContext;
import io.opentelemetry.api.trace.SpanKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuilds one game from immutable revisions; never exposes an intermediate checkpoint. */
public final class GameReconciliationService {
    private static final Duration ABANDONED_CLAIM_AGE = Duration.ofMinutes(5);
    private static final int MAX_EVENTS = 10_000;

    private final JdbcClient jdbc;
    private final JdbcFixtureRepository fixtures;
    private final JdbcGameProcessingRepository processing;
    private final JdbcOutboxRepository outbox;
    private final AlertRuleSource rules;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PersistenceJson json;

    public GameReconciliationService(JdbcClient jdbc, JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processing, JdbcOutboxRepository outbox,
            AlertRuleSource rules, TransactionTemplate transactions, Clock clock,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.fixtures = fixtures;
        this.processing = processing;
        this.outbox = outbox;
        this.rules = rules;
        this.transactions = transactions;
        this.clock = clock;
        this.json = new PersistenceJson(objectMapper);
    }

    /** Additive fixture import. It does not enqueue historical events for forward application. */
    public Submission submit(LoadedFixture fixture) {
        if (fixture.sourceEvents().isEmpty()) {
            throw new IllegalArgumentException("Correction fixture must contain source events");
        }
        try (var span = TraceContext.start("correction ingest", SpanKind.INTERNAL)) {
            return transactions.execute(status -> submitInTransaction(fixture));
        }
    }

    private Submission submitInTransaction(LoadedFixture fixture) {
        Instant now = clock.instant();
        String gameId = fixture.game().gameId();
        String source = fixture.sourceEvents().getFirst().canonicalEvent().source();
        fixtures.ensureGame(source, fixture.game(), now);
        processing.lockCheckpoint(gameId);
        int accepted = 0;
        int duplicates = 0;
        int conflicts = 0;
        long firstAffected = Long.MAX_VALUE;
        String correctionEventId = null;
        for (LoadedSourceEvent item : fixture.sourceEvents()) {
            CanonicalEvent event = item.canonicalEvent();
            if (!gameId.equals(event.gameId()) || !source.equals(event.source())) {
                throw new IllegalArgumentException("Correction event does not belong to fixture game/source");
            }
            String existingHash = jdbc.sql("""
                            SELECT content_hash FROM raw_provider_payloads
                            WHERE source = :source AND game_id = :gameId
                              AND provider_event_id = :providerEventId AND revision = :revision
                            """)
                    .param("source", source).param("gameId", gameId)
                    .param("providerEventId", event.providerEventId())
                    .param("revision", event.revision()).query(String.class).optional().orElse(null);
            String sequenceOwner = jdbc.sql("""
                            SELECT provider_event_id FROM canonical_events
                            WHERE game_id = :gameId AND sequence_number = :sequence
                              AND revision = :revision
                            """)
                    .param("gameId", gameId).param("sequence", event.sequence())
                    .param("revision", event.revision()).query(String.class).optional().orElse(null);
            String conflict = existingHash != null && !existingHash.equals(item.contentHash())
                    ? "RAW_IDENTITY_CONFLICT"
                    : sequenceOwner != null && !sequenceOwner.equals(event.providerEventId())
                            ? "SEQUENCE_REVISION_CONFLICT" : null;
            if (conflict != null) {
                int inserted = jdbc.sql("""
                                INSERT INTO reconciliation_conflict_observations (
                                    id, game_id, source, provider_event_id, sequence_number,
                                    revision, content_hash, payload, reason, observed_at)
                                VALUES (:id, :gameId, :source, :providerEventId, :sequence,
                                    :revision, :hash, CAST(:payload AS JSONB), :reason, :now)
                                ON CONFLICT (game_id, source, provider_event_id, revision, content_hash)
                                DO NOTHING
                                """)
                        .param("id", UUID.randomUUID()).param("gameId", gameId)
                        .param("source", source).param("providerEventId", event.providerEventId())
                        .param("sequence", event.sequence()).param("revision", event.revision())
                        .param("hash", item.contentHash()).param("payload", item.rawPayload())
                        .param("reason", conflict).param("now", SqlTime.offset(now)).update();
                if (inserted == 1) {
                    conflicts++;
                    firstAffected = Math.min(firstAffected, event.sequence());
                } else {
                    duplicates++;
                }
                continue;
            }
            var raw = fixtures.insertOrObserveRaw(item, now);
            if (fixtures.insertCanonical(event, raw.id(), now)) {
                accepted++;
                firstAffected = Math.min(firstAffected, event.sequence());
                correctionEventId = event.eventId();
            } else {
                duplicates++;
            }
        }
        if (accepted > 0 || conflicts > 0) {
            Map<String, Object> params = new HashMap<>();
            params.put("gameId", gameId);
            params.put("firstAffected", firstAffected);
            params.put("eventId", correctionEventId);
            params.put("now", SqlTime.offset(now));
            params.put("blocked", conflicts > 0);
            params.put("traceparent", TraceContext.currentTraceparent());
            jdbc.sql("""
                            INSERT INTO game_reconciliations (
                                game_id, status, first_affected_sequence, generation,
                                correction_event_id, last_error_code, requested_at, updated_at,
                                traceparent)
                            VALUES (:gameId, CASE WHEN :blocked THEN 'BLOCKED' ELSE 'PENDING' END,
                                :firstAffected, 0, :eventId,
                                CASE WHEN :blocked THEN 'ambiguous_revision' ELSE NULL END,
                                :now, :now, :traceparent)
                            ON CONFLICT (game_id) DO UPDATE
                            SET status = CASE WHEN :blocked THEN 'BLOCKED' ELSE 'PENDING' END,
                                first_affected_sequence = CASE
                                    WHEN game_reconciliations.status IN ('COMPLETED', 'UNCHANGED')
                                        THEN :firstAffected
                                    ELSE LEAST(game_reconciliations.first_affected_sequence,
                                        :firstAffected) END,
                                correction_event_id = COALESCE(:eventId,
                                    game_reconciliations.correction_event_id),
                                traceparent = COALESCE(:traceparent, game_reconciliations.traceparent),
                                last_error_code = CASE WHEN :blocked THEN 'ambiguous_revision' ELSE NULL END,
                                requested_revision = game_reconciliations.requested_revision + 1,
                                requested_at = :now, updated_at = :now, completed_at = NULL
                            """).params(params).update();
        }
        return new Submission(accepted, duplicates, conflicts);
    }

    public Result reconcile(String gameId) {
        Claim claim = transactions.execute(status -> claim(gameId));
        if (claim == null) {
            return new Result("SKIPPED", 0, null, null);
        }
        try (var span = TraceContext.continueFrom(claim.traceparent(),
                "game reconcile", SpanKind.CONSUMER)) {
            span.span().setAttribute("courtpulse.reconciliation.outcome.pending", true);
            return transactions.execute(status -> rebuild(claim));
        }
    }

    /** One bounded polling pass; BLOCKED rows are deliberately excluded. */
    public List<Result> reconcileAvailable(int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be 1..50");
        List<String> gameIds = jdbc.sql("""
                        SELECT game_id FROM game_reconciliations
                        WHERE status = 'PENDING'
                           OR (status = 'REBUILDING' AND updated_at <= :cutoff)
                        ORDER BY requested_at, game_id LIMIT :limit
                        """).param("cutoff", SqlTime.offset(clock.instant().minus(ABANDONED_CLAIM_AGE)))
                .param("limit", limit).query(String.class).list();
        return gameIds.stream().map(this::reconcile).toList();
    }

    private Claim claim(String gameId) {
        processing.lockCheckpoint(gameId);
        Instant now = clock.instant();
        var row = jdbc.sql("""
                        SELECT status, generation, requested_revision, first_affected_sequence,
                               correction_event_id, traceparent
                        FROM game_reconciliations WHERE game_id = :gameId FOR UPDATE
                        """).param("gameId", gameId)
                .query((rs, n) -> new Claim(gameId, rs.getInt("generation") + 1,
                        rs.getLong("requested_revision"), rs.getLong("first_affected_sequence"),
                        rs.getString("correction_event_id"), rs.getString("status"),
                        rs.getString("traceparent")))
                .optional().orElse(null);
        if (row == null || !("PENDING".equals(row.status())
                || ("REBUILDING".equals(row.status()) && abandoned(gameId, now)))) {
            return null;
        }
        if ("REBUILDING".equals(row.status())) {
            jdbc.sql("""
                            UPDATE reconciliation_attempts
                            SET status = 'RETRIED', error_code = 'claim_expired', completed_at = :now
                            WHERE game_id = :gameId AND generation = :priorGeneration
                              AND status = 'REBUILDING'
                            """).param("now", SqlTime.offset(now)).param("gameId", gameId)
                    .param("priorGeneration", row.generation() - 1).update();
        }
        jdbc.sql("""
                        UPDATE game_reconciliations
                        SET status = 'REBUILDING', generation = :generation,
                            updated_at = :now, last_error_code = NULL, gap_sequence = NULL
                        WHERE game_id = :gameId
                        """).param("generation", row.generation())
                .param("now", SqlTime.offset(now)).param("gameId", gameId).update();
        jdbc.sql("""
                        INSERT INTO reconciliation_attempts (
                            id, game_id, generation, status, first_affected_sequence,
                            started_at)
                        VALUES (:id, :gameId, :generation, 'REBUILDING', :firstAffected, :now)
                        """).param("id", UUID.randomUUID()).param("gameId", gameId)
                .param("generation", row.generation()).param("firstAffected", row.firstAffected())
                .param("now", SqlTime.offset(now)).update();
        return row;
    }

    private boolean abandoned(String gameId, Instant now) {
        return jdbc.sql("""
                        SELECT updated_at <= :cutoff FROM game_reconciliations WHERE game_id = :gameId
                        """).param("cutoff", SqlTime.offset(now.minus(ABANDONED_CLAIM_AGE)))
                .param("gameId", gameId).query(Boolean.class).single();
    }

    private Result rebuild(Claim claim) {
        Instant now = clock.instant();
        GameState oldState = processing.lockCheckpoint(claim.gameId());
        long currentRevision = jdbc.sql("""
                        SELECT requested_revision FROM game_reconciliations
                        WHERE game_id = :gameId FOR UPDATE
                        """).param("gameId", claim.gameId()).query(Long.class).single();
        List<EventRow> rows = jdbc.sql("""
                        SELECT event_id, raw_payload_id, sequence_number, revision
                        FROM canonical_events WHERE game_id = :gameId
                        ORDER BY sequence_number, revision DESC, event_id
                        LIMIT :limit
                        """).param("gameId", claim.gameId()).param("limit", MAX_EVENTS + 1)
                .query((rs, n) -> new EventRow(rs.getString("event_id"),
                        rs.getObject("raw_payload_id", UUID.class),
                        rs.getLong("sequence_number"), rs.getInt("revision"))).list();
        if (rows.size() > MAX_EVENTS) {
            return blocked(claim, currentRevision, "event_limit", null, List.of(), now);
        }
        GameState state = GameState.initial(oldState.gameId(), oldState.homeTeamId(), oldState.awayTeamId());
        List<EventRow> selected = new ArrayList<>();
        Map<AlertKey, Alert> expectedAlerts = new LinkedHashMap<>();
        Set<ProviderKey> selectedProviderIds = new HashSet<>();
        RunAccumulator run = new RunAccumulator();
        long expectedSequence = 1;
        int index = 0;
        while (index < rows.size()) {
            EventRow first = rows.get(index);
            if (first.sequence() != expectedSequence) {
                return blocked(claim, currentRevision, "sequence_gap", expectedSequence, selected, now);
            }
            int end = index + 1;
            while (end < rows.size() && rows.get(end).sequence() == expectedSequence) end++;
            EventRow chosen = null;
            CanonicalEvent event = null;
            GameState next = null;
            for (int candidate = index; candidate < end; candidate++) {
                EventRow option = rows.get(candidate);
                CanonicalEvent possible = processing.findEvent(option.eventId());
                try {
                    GameState applied = GameReducer.apply(state, possible);
                    chosen = option;
                    event = possible;
                    next = applied;
                    break;
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    // A lower revision may still be the highest valid one.
                }
            }
            if (chosen == null) {
                return blocked(claim, currentRevision, "invalid_event", expectedSequence, selected, now);
            }
            Long conflictRevision = jdbc.sql("""
                            SELECT MAX(revision) FROM reconciliation_conflict_observations
                            WHERE game_id = :gameId AND sequence_number = :sequence
                            """).param("gameId", claim.gameId()).param("sequence", expectedSequence)
                    .query(Long.class).optional().orElse(null);
            if (conflictRevision != null && chosen.revision() <= conflictRevision) {
                return blocked(claim, currentRevision, "ambiguous_revision", expectedSequence, selected, now);
            }
            if (!selectedProviderIds.add(new ProviderKey(event.source(), event.providerEventId()))) {
                return blocked(claim, currentRevision, "provider_reorder", expectedSequence, selected, now);
            }
            ScoringRunFacts facts = run.advance(event);
            AlertRuleBatch batch = rules.relevantRules(event);
            for (var rule : batch.enabledRules()) {
                ScoringRunFacts matching = rule instanceof ScoringRunRule scoringRule && facts != null
                        && scoringRule.teamId().equals(facts.teamId()) ? facts : null;
                rule.evaluate(new RuleEvaluationFacts(state, next, event, matching))
                        .ifPresent(alert -> expectedAlerts.put(new AlertKey(alert.ruleId(), alert.triggerKey()), alert));
            }
            selected.add(chosen);
            state = next;
            expectedSequence++;
            index = end;
        }
        String checksum = StateChecksum.sha256(state);
        String oldChecksum = StateChecksum.sha256(oldState);
        boolean stateChanged = !checksum.equals(oldChecksum);
        Set<String> priorEventIds = new HashSet<>(jdbc.sql("""
                        SELECT event_id FROM processed_events
                        WHERE consumer_name = :consumer AND game_id = :gameId
                        """).param("consumer", JdbcGameProcessingRepository.CONSUMER_NAME)
                .param("gameId", claim.gameId()).query(String.class).list());
        Set<String> selectedEventIds = new HashSet<>();
        for (EventRow row : selected) selectedEventIds.add(row.eventId());
        boolean historyChanged = !priorEventIds.equals(selectedEventIds);
        if (stateChanged) {
            processing.saveCheckpoint(state, checksum, now);
        }
        if (historyChanged) {
            jdbc.sql("DELETE FROM processed_events WHERE consumer_name = :consumer AND game_id = :gameId")
                    .param("consumer", JdbcGameProcessingRepository.CONSUMER_NAME)
                    .param("gameId", claim.gameId()).update();
            for (EventRow row : selected) processing.insertProcessed(processing.findEvent(row.eventId()), now);
        }
        if (stateChanged || historyChanged) {
            jdbc.sql("""
                            UPDATE game_scoring_runs SET team_id = :team, points = :points,
                                start_sequence = :start WHERE game_id = :gameId
                            """).param("team", run.team).param("points", run.points)
                    .param("start", run.start).param("gameId", claim.gameId()).update();
        }
        long stateVersion = jdbc.sql("""
                        SELECT state_version FROM game_checkpoints WHERE game_id = :gameId
                        """).param("gameId", claim.gameId()).query(Long.class).single();
        reconcileAlerts(claim, expectedAlerts, stateVersion, now);
        String resultStatus = stateChanged || historyChanged ? "COMPLETED" : "UNCHANGED";
        finish(claim, currentRevision, resultStatus, null, null, selected, checksum, now);
        if (stateChanged || historyChanged) {
            outbox.insert("GAME_CORRECTION_RESYNC:" + claim.gameId() + ":" + claim.generation(),
                    "GAME", claim.gameId(), "RESYNC_REQUIRED",
                    Map.of("gameId", claim.gameId(), "stateVersion", stateVersion,
                            "reason", "game_correction"), now);
        }
        return new Result(resultStatus, selected.size(), checksum, null);
    }

    private void reconcileAlerts(Claim claim, Map<AlertKey, Alert> expected, long version, Instant now) {
        List<ExistingAlert> existing = jdbc.sql("""
                        SELECT id, rule_id, trigger_key, triggering_event_id, title,
                               context::TEXT AS context, status
                        FROM alert_instances WHERE game_id = :gameId FOR UPDATE
                        """).param("gameId", claim.gameId())
                .query((rs, n) -> new ExistingAlert(rs.getObject("id", UUID.class),
                        rs.getString("rule_id"), rs.getString("trigger_key"),
                        rs.getString("triggering_event_id"), rs.getString("title"),
                        json.readStringMap(rs.getString("context")), rs.getString("status"))).list();
        Map<AlertKey, ExistingAlert> prior = new HashMap<>();
        for (ExistingAlert row : existing) prior.put(new AlertKey(row.ruleId(), row.triggerKey()), row);
        for (ExistingAlert row : existing) {
            Alert justified = expected.get(new AlertKey(row.ruleId(), row.triggerKey()));
            if (justified != null && justified.triggeringEventId().equals(row.eventId())
                    && justified.title().equals(row.title())
                    && justified.context().equals(row.context())) continue;
            if ("CORRECTED".equals(row.status())) continue;
            jdbc.sql("""
                            UPDATE alert_instances SET status = 'CORRECTED',
                                corrected_by_event_id = :eventId, correction_generation = :generation,
                                corrected_at = :now WHERE id = :id
                            """).param("eventId", claim.eventId())
                    .param("generation", claim.generation()).param("now", SqlTime.offset(now))
                    .param("id", row.id()).update();
            jdbc.sql("""
                            UPDATE alert_deliveries SET status = 'CANCELLED',
                                next_attempt_at = NULL, last_error_code = 'alert_corrected',
                                updated_at = :now WHERE alert_id = :alertId AND channel = 'EMAIL'
                                  AND status IN ('PENDING', 'RETRY_SCHEDULED')
                            """).param("now", SqlTime.offset(now)).param("alertId", row.id()).update();
            jdbc.sql("""
                            UPDATE delivery_outbox work SET status = 'FAILED',
                                last_error_code = 'alert_corrected', lease_owner = NULL,
                                lease_until = NULL
                            FROM alert_deliveries delivery
                            WHERE work.delivery_id = delivery.id AND delivery.alert_id = :alertId
                              AND delivery.status = 'CANCELLED'
                              AND work.status IN ('PENDING', 'RETRY_SCHEDULED', 'LEASED')
                            """).param("alertId", row.id()).update();
        }
        for (var entry : expected.entrySet()) {
            if (prior.containsKey(entry.getKey())) continue;
            Alert alert = entry.getValue();
            if (processing.insertAlert(alert, now) && alert.ownerSubject() == null) {
                outbox.insert("ALERT_CREATED:" + alert.triggerKey(), "ALERT", alert.triggerKey(),
                        "ALERT_CREATED", Map.of("gameId", alert.gameId(), "stateVersion", version,
                                "ruleId", alert.ruleId(), "triggerKey", alert.triggerKey(),
                                "triggeringEventId", alert.triggeringEventId()), now);
            }
        }
    }

    private Result blocked(Claim claim, long currentRevision, String error, Long gap,
            List<EventRow> selected, Instant now) {
        finish(claim, currentRevision, "BLOCKED", error, gap, selected, null, now);
        return new Result("BLOCKED", selected.size(), null, error);
    }

    private void finish(Claim claim, long currentRevision, String result, String error,
            Long gap, List<EventRow> selected, String checksum, Instant now) {
        var update = jdbc.sql("""
                        UPDATE reconciliation_attempts SET status = :status, gap_sequence = :gap,
                            selected_events = :selected, state_checksum = :checksum,
                            error_code = :error, completed_at = :now
                        WHERE game_id = :gameId AND generation = :generation
                        """)
                .param("status", result).param("gap", gap).param("selected", selected.size())
                .param("checksum", checksum).param("error", error)
                .param("now", SqlTime.offset(now)).param("gameId", claim.gameId())
                .param("generation", claim.generation());
        if (update.update() != 1) throw new IllegalStateException("Reconciliation attempt was lost");
        for (EventRow row : selected) {
            jdbc.sql("""
                            INSERT INTO reconciliation_selected_events (
                                game_id, generation, sequence_number, event_id, raw_payload_id, revision)
                            VALUES (:gameId, :generation, :sequence, :eventId, :rawId, :revision)
                            """).param("gameId", claim.gameId()).param("generation", claim.generation())
                    .param("sequence", row.sequence()).param("eventId", row.eventId())
                    .param("rawId", row.rawId()).param("revision", row.revision()).update();
        }
        String next = currentRevision == claim.requestedRevision() ? result : "PENDING";
        jdbc.sql("""
                        UPDATE game_reconciliations SET status = :status, gap_sequence = :gap,
                            last_error_code = :error, state_checksum = :checksum,
                            completed_at = :now, updated_at = :now
                        WHERE game_id = :gameId AND generation = :generation
                        """).param("status", next).param("gap", gap)
                .param("error", error).param("checksum", checksum)
                .param("now", SqlTime.offset(now)).param("gameId", claim.gameId())
                .param("generation", claim.generation()).update();
    }

    private record Claim(String gameId, int generation, long requestedRevision,
            long firstAffected, String eventId, String status, String traceparent) {}
    private record EventRow(String eventId, UUID rawId, long sequence, int revision) {}
    private record AlertKey(String ruleId, String triggerKey) {}
    private record ProviderKey(String source, String providerEventId) {}
    private record ExistingAlert(UUID id, String ruleId, String triggerKey, String eventId,
            String title, Map<String, String> context, String status) {}
    public record Submission(int accepted, int duplicates, int conflicts) {}
    public record Result(String status, int selectedEvents, String checksum, String errorCode) {}

    private static final class RunAccumulator {
        private String team;
        private int points;
        private Long start;

        private ScoringRunFacts advance(CanonicalEvent event) {
            if (event.points() == 0) return null;
            int before = event.teamId().equals(team) ? points : 0;
            int after = Math.addExact(before, event.points());
            long runStart = before == 0 ? event.sequence() : start;
            team = event.teamId();
            points = after;
            start = runStart;
            return new ScoringRunFacts(team, before, after, runStart);
        }
    }
}
