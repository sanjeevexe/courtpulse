package com.courtpulse.persistence;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.providers.fixture.FixtureGame;
import com.courtpulse.providers.fixture.LoadedSourceEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcFixtureRepository {
    private final JdbcClient jdbc;
    private final PersistenceJson json;

    public JdbcFixtureRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.json = new PersistenceJson(objectMapper);
    }

    public void resetAll() {
        jdbc.sql("""
                        TRUNCATE TABLE
                            replay_runs, outbox, alert_instances, processed_events,
                            game_checkpoints, canonical_events, raw_provider_payloads, games
                        CASCADE
                        """)
                .update();
    }

    public void ensureGame(String source, FixtureGame game, Instant now) {
        jdbc.sql("""
                        INSERT INTO games (
                            id, source, home_team_id, away_team_id, status, created_at, updated_at)
                        VALUES (:id, :source, :home, :away, 'SCHEDULED', :now, :now)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .params(Map.of(
                        "id", game.gameId(),
                        "source", source,
                        "home", game.homeTeamId(),
                        "away", game.awayTeamId(),
                        "now", SqlTime.offset(now)))
                .update();

        StoredGame stored = jdbc.sql("""
                        SELECT source, home_team_id, away_team_id
                        FROM games
                        WHERE id = :id
                        """)
                .param("id", game.gameId())
                .query((resultSet, rowNumber) -> new StoredGame(
                        resultSet.getString("source"),
                        resultSet.getString("home_team_id"),
                        resultSet.getString("away_team_id")))
                .single();
        if (!stored.source().equals(source)
                || !stored.homeTeamId().equals(game.homeTeamId())
                || !stored.awayTeamId().equals(game.awayTeamId())) {
            throw new IllegalStateException("Existing game metadata conflicts with fixture " + game.gameId());
        }

        jdbc.sql("""
                        INSERT INTO game_checkpoints (
                            game_id, state_version, status, period, clock_millis_remaining,
                            home_score, away_score, last_sequence, player_points,
                            recent_event_ids, state_checksum, updated_at)
                        VALUES (
                            :gameId, 0, 'SCHEDULED', 0, 0, 0, 0, 0,
                            '{}'::JSONB, '[]'::JSONB, NULL, :now)
                        ON CONFLICT (game_id) DO NOTHING
                        """)
                .params(Map.of("gameId", game.gameId(), "now", SqlTime.offset(now)))
                .update();

        UUID systemRuleId = SystemDemoRuleId.forGame(game.gameId());
        jdbc.sql("""
                        INSERT INTO alert_rules (
                            id, owner_subject, game_id, rule_type, enabled, player_id,
                            points_threshold, version, created_at, updated_at)
                        VALUES (
                            :id, NULL, :gameId, 'PLAYER_POINTS', TRUE, 'player_ace',
                            10, 1, :now, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", systemRuleId)
                .param("gameId", game.gameId())
                .param("now", SqlTime.offset(now))
                .update();
        jdbc.sql("""
                        INSERT INTO game_scoring_runs (game_id, team_id, points, start_sequence)
                        VALUES (:gameId, NULL, 0, NULL)
                        ON CONFLICT (game_id) DO NOTHING
                        """)
                .param("gameId", game.gameId())
                .update();
    }

    public RawPayloadInsert insertOrObserveRaw(LoadedSourceEvent sourceEvent, Instant now) {
        CanonicalEvent event = sourceEvent.canonicalEvent();
        UUID id = UUID.nameUUIDFromBytes(("raw:%s:%s:%s:%d"
                        .formatted(event.source(), event.gameId(), event.providerEventId(), event.revision()))
                .getBytes(StandardCharsets.UTF_8));
        int inserted = jdbc.sql("""
                        INSERT INTO raw_provider_payloads (
                            id, source, game_id, provider_event_id, revision, content_hash, payload,
                            first_ingested_at, last_observed_at, observation_count)
                        VALUES (
                            :id, :source, :gameId, :providerEventId, :revision, :contentHash,
                            CAST(:payload AS JSONB), :now, :now, 1)
                        ON CONFLICT (source, game_id, provider_event_id, revision) DO NOTHING
                        """)
                .params(Map.of(
                        "id", id,
                        "source", event.source(),
                        "gameId", event.gameId(),
                        "providerEventId", event.providerEventId(),
                        "revision", event.revision(),
                        "contentHash", sourceEvent.contentHash(),
                        "payload", sourceEvent.rawPayload(),
                        "now", SqlTime.offset(now)))
                .update();

        RawPayloadRow stored = jdbc.sql("""
                        SELECT id, content_hash
                        FROM raw_provider_payloads
                        WHERE source = :source
                          AND game_id = :gameId
                          AND provider_event_id = :providerEventId
                          AND revision = :revision
                        """)
                .params(Map.of(
                        "source", event.source(),
                        "gameId", event.gameId(),
                        "providerEventId", event.providerEventId(),
                        "revision", event.revision()))
                .query((resultSet, rowNumber) -> new RawPayloadRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("content_hash")))
                .single();

        if (!stored.contentHash().equals(sourceEvent.contentHash())) {
            throw new IllegalStateException("Provider identity was re-imported with different raw content: "
                    + event.identity());
        }
        if (inserted == 0) {
            jdbc.sql("""
                            UPDATE raw_provider_payloads
                            SET last_observed_at = :now,
                                observation_count = observation_count + 1
                            WHERE id = :id
                            """)
                    .params(Map.of("now", SqlTime.offset(now), "id", stored.id()))
                    .update();
        }
        return new RawPayloadInsert(stored.id(), inserted == 1);
    }

    public boolean insertCanonical(CanonicalEvent event, UUID rawPayloadId, Instant now) {
        int rows = jdbc.sql("""
                        INSERT INTO canonical_events (
                            event_id, schema_version, source, game_id, provider_event_id,
                            sequence_number, revision, event_type, period, clock_millis_remaining,
                            occurred_at, team_id, participant_ids, home_score, away_score, points,
                            raw_payload_id, canonical_payload, created_at)
                        VALUES (
                            :eventId, :schemaVersion, :source, :gameId, :providerEventId,
                            :sequence, :revision, :eventType, :period, :clock,
                            :occurredAt, :teamId, CAST(:participants AS JSONB), :homeScore,
                            :awayScore, :points, :rawPayloadId, CAST(:canonicalPayload AS JSONB), :now)
                        ON CONFLICT (source, game_id, provider_event_id, revision) DO NOTHING
                        """)
                .params(parameters(event, rawPayloadId, now))
                .update();
        if (rows == 0) {
            String existingEventId = jdbc.sql("""
                            SELECT event_id
                            FROM canonical_events
                            WHERE source = :source
                              AND game_id = :gameId
                              AND provider_event_id = :providerEventId
                              AND revision = :revision
                            """)
                    .params(Map.of(
                            "source", event.source(),
                            "gameId", event.gameId(),
                            "providerEventId", event.providerEventId(),
                            "revision", event.revision()))
                    .query(String.class)
                    .single();
            if (!existingEventId.equals(event.eventId())) {
                throw new IllegalStateException("Canonical provider identity conflicts with event ID "
                        + event.identity());
            }
        }
        return rows == 1;
    }

    private Map<String, Object> parameters(CanonicalEvent event, UUID rawPayloadId, Instant now) {
        java.util.HashMap<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("eventId", event.eventId());
        parameters.put("schemaVersion", event.schemaVersion());
        parameters.put("source", event.source());
        parameters.put("gameId", event.gameId());
        parameters.put("providerEventId", event.providerEventId());
        parameters.put("sequence", event.sequence());
        parameters.put("revision", event.revision());
        parameters.put("eventType", event.type().name());
        parameters.put("period", event.period());
        parameters.put("clock", event.clockMillisRemaining());
        parameters.put("occurredAt", SqlTime.offset(event.occurredAt()));
        parameters.put("teamId", event.teamId());
        parameters.put("participants", json.write(event.participantIds()));
        parameters.put("homeScore", event.scoreAfter().home());
        parameters.put("awayScore", event.scoreAfter().away());
        parameters.put("points", event.points());
        parameters.put("rawPayloadId", rawPayloadId);
        parameters.put("canonicalPayload", json.write(event));
        parameters.put("now", SqlTime.offset(now));
        return parameters;
    }

    public record RawPayloadInsert(UUID id, boolean inserted) {}

    private record StoredGame(String source, String homeTeamId, String awayTeamId) {}

    private record RawPayloadRow(UUID id, String contentHash) {}
}
