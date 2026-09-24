CREATE TABLE game_reconciliations (
    game_id TEXT PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'REBUILDING', 'BLOCKED', 'COMPLETED', 'UNCHANGED')),
    first_affected_sequence BIGINT NOT NULL CHECK (first_affected_sequence > 0),
    gap_sequence BIGINT CHECK (gap_sequence > 0),
    generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
    correction_event_id TEXT REFERENCES canonical_events(event_id) ON DELETE SET NULL,
    requested_revision BIGINT NOT NULL DEFAULT 1 CHECK (requested_revision > 0),
    last_error_code VARCHAR(64),
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    state_checksum CHAR(64)
);

CREATE INDEX idx_game_reconciliations_work
    ON game_reconciliations (status, requested_at, game_id)
    WHERE status IN ('PENDING', 'REBUILDING');

CREATE TABLE reconciliation_attempts (
    id UUID PRIMARY KEY,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    generation INTEGER NOT NULL CHECK (generation > 0),
    status TEXT NOT NULL CHECK (status IN ('REBUILDING', 'RETRIED', 'COMPLETED', 'UNCHANGED', 'BLOCKED')),
    first_affected_sequence BIGINT NOT NULL CHECK (first_affected_sequence > 0),
    gap_sequence BIGINT CHECK (gap_sequence > 0),
    selected_events INTEGER NOT NULL DEFAULT 0 CHECK (selected_events >= 0),
    state_checksum CHAR(64),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_reconciliation_game_generation UNIQUE (game_id, generation)
);

CREATE TABLE reconciliation_selected_events (
    game_id TEXT NOT NULL,
    generation INTEGER NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    event_id TEXT NOT NULL REFERENCES canonical_events(event_id),
    raw_payload_id UUID NOT NULL REFERENCES raw_provider_payloads(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    PRIMARY KEY (game_id, generation, sequence_number),
    FOREIGN KEY (game_id, generation)
        REFERENCES reconciliation_attempts(game_id, generation) ON DELETE CASCADE
);

ALTER TABLE alert_instances
    ADD COLUMN corrected_by_event_id TEXT REFERENCES canonical_events(event_id) ON DELETE SET NULL,
    ADD COLUMN correction_generation INTEGER,
    ADD COLUMN corrected_at TIMESTAMPTZ;

-- A conflicting same-revision observation cannot enter V1's unique raw/canonical
-- identities. Keep the rejected source bytes for operator review without replacing
-- the first accepted observation.
CREATE TABLE reconciliation_conflict_observations (
    id UUID PRIMARY KEY,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    provider_event_id TEXT NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    revision INTEGER NOT NULL CHECK (revision > 0),
    content_hash CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN ('RAW_IDENTITY_CONFLICT', 'SEQUENCE_REVISION_CONFLICT')),
    observed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (game_id, source, provider_event_id, revision, content_hash)
);
