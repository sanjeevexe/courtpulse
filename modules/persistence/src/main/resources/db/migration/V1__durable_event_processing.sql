CREATE TABLE games (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    home_team_id TEXT NOT NULL,
    away_team_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SCHEDULED', 'LIVE', 'FINAL')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (home_team_id <> away_team_id)
);

CREATE TABLE raw_provider_payloads (
    id UUID PRIMARY KEY,
    source TEXT NOT NULL,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    provider_event_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    content_hash CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    first_ingested_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    observation_count INTEGER NOT NULL DEFAULT 1 CHECK (observation_count > 0),
    CONSTRAINT uq_raw_provider_identity UNIQUE (source, game_id, provider_event_id, revision),
    CONSTRAINT uq_raw_content_hash UNIQUE (source, game_id, content_hash)
);

CREATE TABLE canonical_events (
    event_id TEXT PRIMARY KEY,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    source TEXT NOT NULL,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    provider_event_id TEXT NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    revision INTEGER NOT NULL CHECK (revision > 0),
    event_type TEXT NOT NULL,
    period INTEGER NOT NULL CHECK (period BETWEEN 1 AND 4),
    clock_millis_remaining BIGINT NOT NULL CHECK (clock_millis_remaining >= 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    team_id TEXT,
    participant_ids JSONB NOT NULL,
    home_score INTEGER NOT NULL CHECK (home_score >= 0),
    away_score INTEGER NOT NULL CHECK (away_score >= 0),
    points INTEGER NOT NULL CHECK (points BETWEEN 0 AND 3),
    raw_payload_id UUID NOT NULL REFERENCES raw_provider_payloads(id),
    canonical_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_canonical_provider_identity UNIQUE (source, game_id, provider_event_id, revision),
    CONSTRAINT uq_canonical_game_sequence_revision UNIQUE (game_id, sequence_number, revision)
);

CREATE INDEX idx_canonical_events_game_sequence
    ON canonical_events (game_id, sequence_number);

CREATE TABLE game_checkpoints (
    game_id TEXT PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
    state_version BIGINT NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    status TEXT NOT NULL CHECK (status IN ('SCHEDULED', 'LIVE', 'FINAL')),
    period INTEGER NOT NULL CHECK (period BETWEEN 0 AND 4),
    clock_millis_remaining BIGINT NOT NULL CHECK (clock_millis_remaining >= 0),
    home_score INTEGER NOT NULL CHECK (home_score >= 0),
    away_score INTEGER NOT NULL CHECK (away_score >= 0),
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= 0),
    player_points JSONB NOT NULL,
    recent_event_ids JSONB NOT NULL,
    state_checksum CHAR(64),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE processed_events (
    consumer_name TEXT NOT NULL,
    source TEXT NOT NULL,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    provider_event_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    event_id TEXT NOT NULL REFERENCES canonical_events(event_id),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, source, game_id, provider_event_id, revision),
    CONSTRAINT uq_processed_consumer_event UNIQUE (consumer_name, event_id)
);

CREATE TABLE alert_instances (
    id UUID PRIMARY KEY,
    rule_id TEXT NOT NULL,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    trigger_key TEXT NOT NULL,
    triggering_event_id TEXT NOT NULL REFERENCES canonical_events(event_id),
    title TEXT NOT NULL,
    context JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('CREATED', 'CORRECTED')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_alert_rule_trigger UNIQUE (rule_id, trigger_key)
);

CREATE INDEX idx_alert_instances_game_created
    ON alert_instances (game_id, created_at DESC);

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    deduplication_key TEXT NOT NULL UNIQUE,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'PUBLISHING', 'SENT', 'RETRY_SCHEDULED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending
    ON outbox (status, next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

CREATE TABLE replay_runs (
    id UUID PRIMARY KEY,
    fixture_name TEXT NOT NULL,
    mode TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    accepted_events BIGINT NOT NULL DEFAULT 0 CHECK (accepted_events >= 0),
    suppressed_duplicates BIGINT NOT NULL DEFAULT 0 CHECK (suppressed_duplicates >= 0),
    final_state_checksum CHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
