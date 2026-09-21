DROP INDEX idx_canonical_events_game_sequence;
CREATE INDEX idx_canonical_events_game_order
    ON canonical_events (game_id, sequence_number, revision, event_id);

CREATE INDEX idx_game_checkpoints_updated_game
    ON game_checkpoints (updated_at DESC, game_id);

DROP INDEX idx_alert_instances_game_created;
CREATE INDEX idx_alert_instances_game_order
    ON alert_instances (game_id, created_at DESC, trigger_key);

CREATE INDEX idx_outbox_game_group_status
    ON outbox (message_group_id, status, created_at)
    WHERE destination = 'GAME_EVENTS';
