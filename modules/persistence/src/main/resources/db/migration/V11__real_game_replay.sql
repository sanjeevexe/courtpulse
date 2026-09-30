-- Real completed games imported from public NBA play-by-play, replayed on demand as if live.
-- Each replay session becomes an ordinary provider game (source 'nba-replay'), so replays flow
-- through the same ingestion, processing, alert, and realtime paths as a live feed.

CREATE TABLE replay_catalog (
    nba_game_id TEXT PRIMARY KEY CHECK (nba_game_id ~ '^[0-9]{10}$'),
    dataset TEXT NOT NULL CHECK (dataset ~ '^[a-z0-9_]{1,40}$'),
    game_date DATE NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    home_team_id BIGINT NOT NULL,
    home_tricode VARCHAR(5) NOT NULL,
    home_name VARCHAR(60) NOT NULL,
    away_team_id BIGINT NOT NULL,
    away_tricode VARCHAR(5) NOT NULL,
    away_name VARCHAR(60) NOT NULL,
    home_score INTEGER NOT NULL CHECK (home_score >= 0),
    away_score INTEGER NOT NULL CHECK (away_score >= 0),
    periods INTEGER NOT NULL CHECK (periods BETWEEN 1 AND 10),
    action_count INTEGER NOT NULL CHECK (action_count > 0),
    duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0),
    imported_at TIMESTAMPTZ NOT NULL,
    CHECK (home_team_id <> away_team_id)
);

CREATE INDEX idx_replay_catalog_recent ON replay_catalog (started_at DESC, nba_game_id DESC);

CREATE TABLE replay_actions (
    nba_game_id TEXT NOT NULL REFERENCES replay_catalog(nba_game_id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 1),
    offset_ms BIGINT NOT NULL CHECK (offset_ms >= 0),
    payload JSONB NOT NULL CHECK (octet_length(payload::TEXT) <= 8192),
    PRIMARY KEY (nba_game_id, ordinal)
);

CREATE TABLE replay_players (
    person_id BIGINT PRIMARY KEY CHECK (person_id > 0),
    display_name VARCHAR(80) NOT NULL
);

-- Replay time at instant t: elapsed_ms + (status = 'RUNNING' ? (t - anchor_at) * speed : 0).
-- Pausing, resuming, and changing speed fold the running segment into elapsed_ms.
CREATE TABLE replay_sessions (
    id UUID PRIMARY KEY,
    nba_game_id TEXT NOT NULL REFERENCES replay_catalog(nba_game_id) ON DELETE CASCADE,
    run_number INTEGER NOT NULL CHECK (run_number >= 1),
    game_id TEXT NOT NULL UNIQUE,
    speed INTEGER NOT NULL CHECK (speed BETWEEN 1 AND 120),
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'PAUSED', 'FINISHED')),
    elapsed_ms BIGINT NOT NULL CHECK (elapsed_ms >= 0),
    anchor_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (nba_game_id, run_number)
);

CREATE INDEX idx_replay_sessions_active ON replay_sessions (status, updated_at DESC);
CREATE INDEX idx_replay_sessions_owner ON replay_sessions (created_by, status);

ALTER TABLE worker_heartbeats DROP CONSTRAINT ck_worker_heartbeat_type;
ALTER TABLE worker_heartbeats ADD CONSTRAINT ck_worker_heartbeat_type
    CHECK (worker_type IN ('delivery', 'reconciliation', 'processor', 'ingestor', 'replay'));
