ALTER TABLE outbox
    ADD COLUMN destination TEXT,
    ADD COLUMN message_group_id TEXT,
    ADD COLUMN lease_owner TEXT,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_error VARCHAR(1000);

UPDATE outbox
SET destination = CASE
        WHEN event_type = 'CANONICAL_EVENT_READY' THEN 'GAME_EVENTS'
        ELSE 'FUTURE_NOTIFICATIONS'
    END,
    message_group_id = payload ->> 'gameId';

ALTER TABLE outbox
    ALTER COLUMN destination SET NOT NULL;

ALTER TABLE outbox
    ADD CONSTRAINT ck_outbox_destination
        CHECK (destination IN ('GAME_EVENTS', 'FUTURE_NOTIFICATIONS')),
    ADD CONSTRAINT ck_outbox_lease
        CHECK (
            (status = 'PUBLISHING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR
            (status <> 'PUBLISHING' AND lease_owner IS NULL AND lease_expires_at IS NULL)
        ),
    ADD CONSTRAINT ck_game_event_group
        CHECK (destination <> 'GAME_EVENTS' OR message_group_id IS NOT NULL);

DROP INDEX idx_outbox_pending;

CREATE INDEX idx_outbox_publishable
    ON outbox (destination, status, next_attempt_at, created_at, id)
    WHERE destination = 'GAME_EVENTS'
      AND status IN ('PENDING', 'RETRY_SCHEDULED', 'PUBLISHING');

CREATE INDEX idx_outbox_group_order
    ON outbox (destination, message_group_id, created_at, id)
    WHERE destination = 'GAME_EVENTS';
