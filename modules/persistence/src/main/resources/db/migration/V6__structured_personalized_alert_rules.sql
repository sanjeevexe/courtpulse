CREATE TABLE alert_rules (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) REFERENCES application_users(subject) ON DELETE CASCADE,
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    rule_type TEXT NOT NULL CHECK (rule_type IN ('PLAYER_POINTS', 'CLOSE_GAME', 'SCORING_RUN')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    player_id VARCHAR(200),
    team_id VARCHAR(200),
    points_threshold INTEGER,
    maximum_margin INTEGER,
    eligible_period INTEGER,
    maximum_clock_millis_remaining BIGINT,
    client_request_id VARCHAR(100),
    request_fingerprint CHAR(64),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_alert_rules_owner CHECK (
        (owner_subject IS NULL AND rule_type = 'PLAYER_POINTS'
            AND enabled AND player_id = 'player_ace' AND points_threshold = 10
            AND client_request_id IS NULL AND request_fingerprint IS NULL)
        OR
        (owner_subject IS NOT NULL AND client_request_id IS NOT NULL
            AND request_fingerprint IS NOT NULL
            AND request_fingerprint ~ '^[0-9a-f]{64}$'
            AND char_length(client_request_id) BETWEEN 1 AND 100
            AND client_request_id ~ '^[A-Za-z0-9._:-]+$')
    ),
    CONSTRAINT ck_alert_rules_shape CHECK (
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
            AND eligible_period BETWEEN 1 AND 4
            AND maximum_clock_millis_remaining BETWEEN 0 AND 720000)
        OR
        (rule_type = 'SCORING_RUN'
            AND player_id IS NULL AND team_id IS NOT NULL AND char_length(team_id) BETWEEN 1 AND 200
            AND team_id = btrim(team_id, E' \t\n\r\f')
            AND team_id ~ '[^[:space:]]'
            AND points_threshold BETWEEN 1 AND 100
            AND maximum_margin IS NULL AND eligible_period IS NULL
            AND maximum_clock_millis_remaining IS NULL)
    )
);

CREATE UNIQUE INDEX uq_alert_rules_owner_request
    ON alert_rules (owner_subject, client_request_id)
    WHERE owner_subject IS NOT NULL AND client_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_alert_rules_system_player_demo
    ON alert_rules (game_id, rule_type, player_id, points_threshold)
    WHERE owner_subject IS NULL;

CREATE INDEX idx_alert_rules_owner_order
    ON alert_rules (owner_subject, created_at DESC, id)
    WHERE owner_subject IS NOT NULL;

CREATE INDEX idx_alert_rules_game_player
    ON alert_rules (game_id, player_id, id)
    WHERE enabled AND rule_type = 'PLAYER_POINTS';

CREATE INDEX idx_alert_rules_game_team
    ON alert_rules (game_id, team_id, id)
    WHERE enabled AND rule_type = 'SCORING_RUN';

CREATE INDEX idx_alert_rules_game_close
    ON alert_rules (game_id, eligible_period, maximum_clock_millis_remaining, maximum_margin, id)
    WHERE enabled AND rule_type = 'CLOSE_GAME';

CREATE INDEX idx_processed_events_consumer_game
    ON processed_events (consumer_name, game_id, event_id);

-- One bounded run accumulator per game. Its update commits with the event checkpoint.
CREATE TABLE game_scoring_runs (
    game_id TEXT PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
    team_id VARCHAR(200),
    points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
    start_sequence BIGINT,
    CONSTRAINT ck_game_scoring_runs_shape CHECK (
        (team_id IS NULL AND points = 0 AND start_sequence IS NULL)
        OR (team_id IS NOT NULL AND char_length(team_id) BETWEEN 1 AND 200
            AND team_id = btrim(team_id) AND points > 0 AND start_sequence > 0)
    )
);

-- Upgrade existing processed games once; runtime processing never rescans history for run facts.
INSERT INTO game_scoring_runs (game_id, team_id, points, start_sequence)
SELECT game.id, latest.team_id, COALESCE(run.points, 0), run.start_sequence
FROM games game
LEFT JOIN LATERAL (
    SELECT event.team_id
    FROM processed_events processed
    JOIN canonical_events event ON event.event_id = processed.event_id
    WHERE processed.consumer_name = 'durable-game-processor-v1'
      AND processed.game_id = game.id AND event.points > 0
    ORDER BY event.sequence_number DESC, event.revision DESC
    LIMIT 1
) latest ON TRUE
LEFT JOIN LATERAL (
    SELECT COALESCE(MAX(event.sequence_number), 0) AS sequence_number
    FROM processed_events processed
    JOIN canonical_events event ON event.event_id = processed.event_id
    WHERE processed.consumer_name = 'durable-game-processor-v1'
      AND processed.game_id = game.id AND event.points > 0
      AND event.team_id IS DISTINCT FROM latest.team_id
) opponent ON TRUE
LEFT JOIN LATERAL (
    SELECT COALESCE(SUM(event.points), 0)::INTEGER AS points,
           MIN(event.sequence_number) AS start_sequence
    FROM processed_events processed
    JOIN canonical_events event ON event.event_id = processed.event_id
    WHERE processed.consumer_name = 'durable-game-processor-v1'
      AND processed.game_id = game.id AND event.points > 0
      AND event.team_id = latest.team_id
      AND event.sequence_number > opponent.sequence_number
) run ON TRUE;

INSERT INTO alert_rules (
    id, owner_subject, game_id, rule_type, enabled, player_id,
    points_threshold, version, created_at, updated_at)
SELECT CAST(md5('system-rule:' || game.id || ':player_ace:10') AS UUID),
       NULL, game.id, 'PLAYER_POINTS', TRUE, 'player_ace', 10, 1,
       game.created_at, game.updated_at
FROM games game
ON CONFLICT DO NOTHING;

ALTER TABLE alert_instances
    ADD COLUMN owner_subject VARCHAR(255) REFERENCES application_users(subject) ON DELETE CASCADE,
    ADD COLUMN rule_type TEXT;

UPDATE alert_instances SET rule_type = 'PLAYER_POINTS';

ALTER TABLE alert_instances
    ALTER COLUMN rule_type SET NOT NULL,
    ADD CONSTRAINT ck_alert_instances_rule_type
        CHECK (rule_type IN ('PLAYER_POINTS', 'CLOSE_GAME', 'SCORING_RUN'));

CREATE INDEX idx_alert_instances_owner_order
    ON alert_instances (owner_subject, created_at DESC, id)
    WHERE owner_subject IS NOT NULL;
