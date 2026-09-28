-- Trace context is opaque operational metadata, not a private payload or a durable identity.
ALTER TABLE outbox ADD COLUMN traceparent VARCHAR(55);
ALTER TABLE delivery_outbox ADD COLUMN traceparent VARCHAR(55);
ALTER TABLE game_reconciliations ADD COLUMN traceparent VARCHAR(55);

CREATE TABLE worker_heartbeats (
    worker_type VARCHAR(32) PRIMARY KEY,
    observed_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_worker_heartbeat_type CHECK (worker_type IN ('delivery', 'reconciliation')),
    CONSTRAINT ck_worker_failures_nonnegative CHECK (consecutive_failures >= 0)
);

CREATE TABLE queue_observations (
    queue_type VARCHAR(32) PRIMARY KEY,
    visible BIGINT NOT NULL CHECK (visible >= 0),
    in_flight BIGINT NOT NULL CHECK (in_flight >= 0),
    delayed BIGINT NOT NULL CHECK (delayed >= 0),
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_queue_observation_type CHECK (queue_type IN
        ('game_events', 'game_events_dlq', 'alert_deliveries', 'alert_deliveries_dlq'))
);

CREATE TABLE operational_counters (
    counter_name VARCHAR(40) PRIMARY KEY,
    value BIGINT NOT NULL DEFAULT 0 CHECK (value >= 0),
    CONSTRAINT ck_operational_counter_name CHECK (counter_name = 'game_event_duplicate')
);
