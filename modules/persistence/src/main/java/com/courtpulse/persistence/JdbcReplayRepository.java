package com.courtpulse.persistence;

import com.courtpulse.providers.nba.NbaAction;
import com.courtpulse.providers.nba.NbaReplayGame;
import com.courtpulse.providers.nba.NbaReplayPlayMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real-game catalog, replayable actions, and replay sessions. */
public final class JdbcReplayRepository {
    private static final long SESSION_LOCK = 0x5245504c4159L; // "REPLAY"
    private static final Pattern PROVIDER_GAME_ID = Pattern.compile("^([0-9]{10})-([0-9]{1,6})$");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final NbaReplayPlayMapper plays;

    public JdbcReplayRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.plays = new NbaReplayPlayMapper(mapper);
    }

    public record CatalogGame(
            String nbaGameId,
            String dataset,
            LocalDate gameDate,
            Instant startedAt,
            NbaReplayGame.Team home,
            NbaReplayGame.Team away,
            int homeScore,
            int awayScore,
            int periods,
            int actionCount,
            long durationMillis) {}

    /** Stores one game, replacing any earlier import of it. Existing replays keep their game rows. */
    public void saveGame(String dataset, NbaReplayGame game, Instant now) {
        jdbc.sql("""
                        INSERT INTO replay_catalog (
                            nba_game_id, dataset, game_date, started_at,
                            home_team_id, home_tricode, home_name, away_team_id, away_tricode, away_name,
                            home_score, away_score, periods, action_count, duration_ms, imported_at)
                        VALUES (:id, :dataset, :date, :startedAt, :homeId, :homeCode, :homeName,
                                :awayId, :awayCode, :awayName, :homeScore, :awayScore, :periods,
                                :actions, :duration, :now)
                        ON CONFLICT (nba_game_id) DO UPDATE SET
                            dataset = EXCLUDED.dataset, game_date = EXCLUDED.game_date,
                            started_at = EXCLUDED.started_at,
                            home_team_id = EXCLUDED.home_team_id, home_tricode = EXCLUDED.home_tricode,
                            home_name = EXCLUDED.home_name, away_team_id = EXCLUDED.away_team_id,
                            away_tricode = EXCLUDED.away_tricode, away_name = EXCLUDED.away_name,
                            home_score = EXCLUDED.home_score, away_score = EXCLUDED.away_score,
                            periods = EXCLUDED.periods, action_count = EXCLUDED.action_count,
                            duration_ms = EXCLUDED.duration_ms, imported_at = EXCLUDED.imported_at
                        """)
                .params(Map.ofEntries(
                        Map.entry("id", game.nbaGameId()), Map.entry("dataset", dataset),
                        Map.entry("date", game.gameDate()), Map.entry("startedAt", SqlTime.offset(game.startedAt())),
                        Map.entry("homeId", game.home().teamId()), Map.entry("homeCode", game.home().tricode()),
                        Map.entry("homeName", game.home().name()), Map.entry("awayId", game.away().teamId()),
                        Map.entry("awayCode", game.away().tricode()), Map.entry("awayName", game.away().name()),
                        Map.entry("homeScore", game.homeScore()), Map.entry("awayScore", game.awayScore()),
                        Map.entry("periods", game.periods()), Map.entry("actions", game.actions().size()),
                        Map.entry("duration", game.durationMillis()), Map.entry("now", SqlTime.offset(now))))
                .update();
        jdbc.sql("DELETE FROM replay_actions WHERE nba_game_id = :id").param("id", game.nbaGameId()).update();
        Map<Long, String> players = new HashMap<>();
        for (NbaReplayGame.TimedAction timed : game.actions()) {
            jdbc.sql("""
                            INSERT INTO replay_actions (nba_game_id, ordinal, offset_ms, payload)
                            VALUES (:id, :ordinal, :offset, CAST(:payload AS JSONB))
                            """)
                    .param("id", game.nbaGameId()).param("ordinal", timed.ordinal())
                    .param("offset", timed.offsetMillis()).param("payload", plays.rawPayload(timed.action()))
                    .update();
            NbaAction action = timed.action();
            if (action.personId() > 0 && action.personId() != action.teamId() && !action.playerNameI().isBlank()) {
                players.putIfAbsent(action.personId(), action.playerNameI());
            }
        }
        players.forEach((personId, name) -> jdbc.sql("""
                        INSERT INTO replay_players (person_id, display_name) VALUES (:id, :name)
                        ON CONFLICT (person_id) DO UPDATE SET display_name = EXCLUDED.display_name
                        """)
                .param("id", personId).param("name", name.length() > 80 ? name.substring(0, 80) : name)
                .update());
    }

    /** Newest games first; optionally one team's games; keyset-paged by the last game returned. */
    public List<CatalogGame> catalog(String tricode, String afterNbaGameId, int limit) {
        if (limit < 1 || limit > 201) {
            throw new IllegalArgumentException("limit must be between 1 and 201");
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM replay_catalog game WHERE TRUE");
        Map<String, Object> parameters = new HashMap<>();
        if (tricode != null) {
            sql.append(" AND (game.home_tricode = :team OR game.away_tricode = :team)");
            parameters.put("team", tricode);
        }
        if (afterNbaGameId != null) {
            sql.append("""
                     AND (game.started_at, game.nba_game_id) < (
                        SELECT started_at, nba_game_id FROM replay_catalog WHERE nba_game_id = :after)
                    """);
            parameters.put("after", afterNbaGameId);
        }
        sql.append(" ORDER BY game.started_at DESC, game.nba_game_id DESC LIMIT :limit");
        parameters.put("limit", limit);
        return jdbc.sql(sql.toString()).params(parameters).query(JdbcReplayRepository::catalogGame).list();
    }

    public Optional<CatalogGame> catalogGame(String nbaGameId) {
        return jdbc.sql("SELECT * FROM replay_catalog WHERE nba_game_id = :id")
                .param("id", nbaGameId).query(JdbcReplayRepository::catalogGame).optional();
    }

    /** Catalog games that were imported from a dataset other than this one. */
    public Set<String> gamesFromOtherDatasets(String dataset) {
        return Set.copyOf(jdbc.sql("SELECT nba_game_id FROM replay_catalog WHERE dataset <> :dataset")
                .param("dataset", dataset).query(String.class).list());
    }

    public int catalogSize() {
        return jdbc.sql("SELECT count(*) FROM replay_catalog").query(Integer.class).single();
    }

    /** Starts a new run of a catalog game; each run is a separate CourtPulse game. */
    public Optional<ReplaySession> createSession(String nbaGameId, int speed, String createdBy, Instant now) {
        ReplaySession.requireSpeed(speed);
        Optional<String> locked = jdbc.sql("SELECT nba_game_id FROM replay_catalog WHERE nba_game_id = :id FOR UPDATE")
                .param("id", nbaGameId).query(String.class).optional();
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        int run = jdbc.sql("SELECT COALESCE(max(run_number), 0) + 1 FROM replay_sessions WHERE nba_game_id = :id")
                .param("id", nbaGameId).query(Integer.class).single();
        ReplaySession session = new ReplaySession(UUID.randomUUID(), nbaGameId, run,
                NbaReplayPlayMapper.gameId(nbaGameId + "-" + run), speed, ReplaySession.Status.RUNNING, 0, now,
                createdBy, now, now, 1);
        jdbc.sql("""
                        INSERT INTO replay_sessions (id, nba_game_id, run_number, game_id, speed, status,
                            elapsed_ms, anchor_at, created_by, created_at, updated_at, version)
                        VALUES (:id, :nbaGameId, :run, :gameId, :speed, :status, :elapsed, :anchor,
                            :createdBy, :now, :now, 1)
                        """)
                .param("id", session.id()).param("nbaGameId", nbaGameId).param("run", run)
                .param("gameId", session.gameId()).param("speed", speed).param("status", session.status().name())
                .param("elapsed", 0L).param("anchor", SqlTime.offset(now)).param("createdBy", createdBy)
                .param("now", SqlTime.offset(now))
                .update();
        return Optional.of(session);
    }

    public Optional<ReplaySession> session(UUID id) {
        return jdbc.sql("SELECT * FROM replay_sessions WHERE id = :id")
                .param("id", id).query(JdbcReplayRepository::session).optional();
    }

    public Optional<ReplaySession> sessionForProviderGame(String providerGameId) {
        Matcher match = PROVIDER_GAME_ID.matcher(providerGameId);
        if (!match.matches()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT * FROM replay_sessions WHERE nba_game_id = :id AND run_number = :run")
                .param("id", match.group(1)).param("run", Integer.parseInt(match.group(2)))
                .query(JdbcReplayRepository::session).optional();
    }

    /** Running and paused replays, plus replays that finished after {@code finishedSince}. */
    public List<ReplaySession> activeSessions(Instant finishedSince) {
        return jdbc.sql("""
                        SELECT * FROM replay_sessions
                        WHERE status IN ('RUNNING', 'PAUSED')
                           OR (status = 'FINISHED' AND updated_at >= :since)
                        ORDER BY created_at DESC, id
                        LIMIT 100
                        """)
                .param("since", SqlTime.offset(finishedSince))
                .query(JdbcReplayRepository::session).list();
    }

    /** Serializes quota checks and session creation for the current transaction. */
    public void lockSessions() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", SESSION_LOCK)
                .query((row, index) -> Boolean.TRUE).single();
    }

    public int countUnfinished(String createdBy) {
        return jdbc.sql("""
                        SELECT count(*) FROM replay_sessions
                        WHERE status IN ('RUNNING', 'PAUSED')
                          AND (CAST(:owner AS VARCHAR) IS NULL OR created_by = :owner)
                        """)
                .param("owner", createdBy).query(Integer.class).single();
    }

    /** Applies a state change only if nobody changed the session since {@code current} was read. */
    public boolean update(ReplaySession current, ReplaySession next) {
        return jdbc.sql("""
                        UPDATE replay_sessions
                        SET speed = :speed, status = :status, elapsed_ms = :elapsed, anchor_at = :anchor,
                            updated_at = :updated, version = :nextVersion
                        WHERE id = :id AND version = :version
                        """)
                .param("speed", next.speed()).param("status", next.status().name())
                .param("elapsed", next.elapsedMillis()).param("anchor", SqlTime.offset(next.anchorAt()))
                .param("updated", SqlTime.offset(next.updatedAt())).param("nextVersion", next.version())
                .param("id", current.id()).param("version", current.version())
                .update() == 1;
    }

    /** Actions whose replay offset has been reached, in provider order. */
    public List<NbaReplayGame.TimedAction> releasedActions(String nbaGameId, long elapsedMillis) {
        return jdbc.sql("""
                        SELECT ordinal, offset_ms, payload::TEXT AS payload FROM replay_actions
                        WHERE nba_game_id = :id AND offset_ms <= :elapsed
                        ORDER BY ordinal
                        """)
                .param("id", nbaGameId).param("elapsed", elapsedMillis)
                .query((row, index) -> new NbaReplayGame.TimedAction(row.getInt("ordinal"), row.getLong("offset_ms"),
                        action(row.getString("payload"))))
                .list();
    }

    public int releasedCount(String nbaGameId, long elapsedMillis) {
        return jdbc.sql("SELECT count(*) FROM replay_actions WHERE nba_game_id = :id AND offset_ms <= :elapsed")
                .param("id", nbaGameId).param("elapsed", elapsedMillis).query(Integer.class).single();
    }

    public Optional<String> playerName(long personId) {
        return jdbc.sql("SELECT display_name FROM replay_players WHERE person_id = :id")
                .param("id", personId).query(String.class).optional();
    }

    private NbaAction action(String payload) {
        try {
            return mapper.readValue(payload, NbaAction.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored replay action is unreadable", exception);
        }
    }

    private static CatalogGame catalogGame(ResultSet row, int index) throws SQLException {
        return new CatalogGame(
                row.getString("nba_game_id"), row.getString("dataset"),
                row.getObject("game_date", LocalDate.class),
                row.getObject("started_at", OffsetDateTime.class).toInstant(),
                new NbaReplayGame.Team(row.getLong("home_team_id"), row.getString("home_tricode"),
                        row.getString("home_name")),
                new NbaReplayGame.Team(row.getLong("away_team_id"), row.getString("away_tricode"),
                        row.getString("away_name")),
                row.getInt("home_score"), row.getInt("away_score"), row.getInt("periods"),
                row.getInt("action_count"), row.getLong("duration_ms"));
    }

    private static ReplaySession session(ResultSet row, int index) throws SQLException {
        return new ReplaySession(
                row.getObject("id", UUID.class), row.getString("nba_game_id"), row.getInt("run_number"),
                row.getString("game_id"), row.getInt("speed"),
                ReplaySession.Status.valueOf(row.getString("status")), row.getLong("elapsed_ms"),
                row.getObject("anchor_at", OffsetDateTime.class).toInstant(), row.getString("created_by"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(), row.getLong("version"));
    }
}
