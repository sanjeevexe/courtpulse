-- M12: overtime periods, provider display metadata, live-provider freshness, and continuous
-- worker types. Expand-only: new columns are nullable or defaulted so the prior release can
-- still read and write every row it understands (overtime rows require this release).

ALTER TABLE canonical_events DROP CONSTRAINT canonical_events_period_check;
ALTER TABLE canonical_events ADD CONSTRAINT canonical_events_period_check
    CHECK (period BETWEEN 1 AND 10);
ALTER TABLE canonical_events ADD CONSTRAINT ck_canonical_events_period_clock
    CHECK (clock_millis_remaining <= CASE WHEN period <= 4 THEN 720000 ELSE 300000 END);
-- Provider play text is display-only; it is bounded, trimmed, and free of control characters.
ALTER TABLE canonical_events ADD COLUMN description VARCHAR(280)
    CONSTRAINT ck_canonical_events_description CHECK (description IS NULL OR (
        char_length(description) BETWEEN 1 AND 280
        AND description = btrim(description)
        AND description !~ '[[:cntrl:]]'));

-- The ingestor looks up unresolved player names among recent events for its source each cycle.
CREATE INDEX idx_canonical_events_source_created ON canonical_events (source, created_at);

ALTER TABLE game_checkpoints DROP CONSTRAINT game_checkpoints_period_check;
ALTER TABLE game_checkpoints ADD CONSTRAINT game_checkpoints_period_check
    CHECK (period BETWEEN 0 AND 10);

ALTER TABLE alert_rules DROP CONSTRAINT ck_alert_rules_shape;
ALTER TABLE alert_rules ADD CONSTRAINT ck_alert_rules_shape CHECK (
    (rule_type = 'PLAYER_POINTS'
        AND player_id IS NOT NULL AND char_length(player_id) BETWEEN 1 AND 200
        AND player_id = btrim(player_id, E' \t\n\r\f')
        AND player_id ~ '[^[:space:]]' AND team_id IS NULL
        AND points_threshold BETWEEN 1 AND 200
        AND maximum_margin IS NULL AND eligible_period IS NULL
        AND maximum_clock_millis_remaining IS NULL)
    OR
    (rule_type = 'CLOSE_GAME'
        AND player_id IS NULL AND team_id IS NULL AND points_threshold IS NULL
        AND maximum_margin BETWEEN 1 AND 20
        AND eligible_period BETWEEN 1 AND 10
        AND maximum_clock_millis_remaining BETWEEN 0 AND 720000)
    OR
    (rule_type = 'SCORING_RUN'
        AND player_id IS NULL AND team_id IS NOT NULL AND char_length(team_id) BETWEEN 1 AND 200
        AND team_id = btrim(team_id, E' \t\n\r\f')
        AND team_id ~ '[^[:space:]]'
        AND points_threshold BETWEEN 1 AND 100
        AND maximum_margin IS NULL AND eligible_period IS NULL
        AND maximum_clock_millis_remaining IS NULL)
);

-- Display names are public provider metadata. Unknown names fall back to stable IDs.
CREATE TABLE teams (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    provider_team_id TEXT NOT NULL,
    name VARCHAR(120) NOT NULL CHECK (char_length(btrim(name)) > 0),
    abbreviation VARCHAR(8) NOT NULL CHECK (abbreviation ~ '^[A-Z0-9]{2,8}$'),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_teams_provider UNIQUE (source, provider_team_id)
);

CREATE TABLE players (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    provider_player_id TEXT NOT NULL,
    display_name VARCHAR(120) NOT NULL CHECK (char_length(btrim(display_name)) > 0),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_players_provider UNIQUE (source, provider_player_id)
);

ALTER TABLE games ADD COLUMN provider_game_id TEXT;
ALTER TABLE games ADD COLUMN scheduled_at TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_games_provider_game
    ON games (source, provider_game_id) WHERE provider_game_id IS NOT NULL;

-- One row per tracked provider game; freshness is the last successful play poll.
CREATE TABLE provider_game_observations (
    game_id TEXT PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    provider_game_id TEXT NOT NULL,
    provider_status VARCHAR(40) NOT NULL,
    lifecycle TEXT NOT NULL CHECK (lifecycle IN ('SCHEDULED', 'LIVE', 'FINAL')),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    observed_plays INTEGER NOT NULL DEFAULT 0 CHECK (observed_plays >= 0),
    last_new_play_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provider_game_observation UNIQUE (source, provider_game_id)
);

CREATE INDEX idx_provider_game_observations_lifecycle
    ON provider_game_observations (lifecycle, last_success_at);

-- Cumulative, process-independent provider client health (fixed vocabulary, no payloads).
CREATE TABLE provider_health (
    source TEXT PRIMARY KEY,
    circuit_state TEXT NOT NULL CHECK (circuit_state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    requests_total BIGINT NOT NULL DEFAULT 0 CHECK (requests_total >= 0),
    rate_limited_total BIGINT NOT NULL DEFAULT 0 CHECK (rate_limited_total >= 0),
    failures_total BIGINT NOT NULL DEFAULT 0 CHECK (failures_total >= 0),
    updated_at TIMESTAMPTZ NOT NULL
);

-- Provider evidence the adapter refused to turn into canonical events. The bounded payload is
-- licensed provider data for diagnosis only and never leaves operator-only storage.
CREATE TABLE provider_data_incidents (
    id UUID PRIMARY KEY,
    source TEXT NOT NULL,
    game_id TEXT REFERENCES games(id) ON DELETE CASCADE,
    provider_event_id TEXT,
    reason TEXT NOT NULL CHECK (reason IN (
        'MAPPING_REJECTED', 'REVERTED_CONTENT', 'PLAY_REMOVED', 'MALFORMED_RESPONSE')),
    detail VARCHAR(200) NOT NULL,
    payload JSONB,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_provider_incident_payload_size
        CHECK (payload IS NULL OR octet_length(payload::TEXT) <= 16384)
);

CREATE UNIQUE INDEX uq_provider_data_incident_identity
    ON provider_data_incidents (source, game_id, provider_event_id, reason)
    WHERE provider_event_id IS NOT NULL;

ALTER TABLE worker_heartbeats DROP CONSTRAINT ck_worker_heartbeat_type;
ALTER TABLE worker_heartbeats ADD CONSTRAINT ck_worker_heartbeat_type
    CHECK (worker_type IN ('delivery', 'reconciliation', 'processor', 'ingestor'));
