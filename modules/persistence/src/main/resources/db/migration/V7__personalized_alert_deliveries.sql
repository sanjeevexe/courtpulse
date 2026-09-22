CREATE TABLE notification_preferences (
    owner_subject VARCHAR(255) PRIMARY KEY REFERENCES application_users(subject) ON DELETE CASCADE,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_notification_in_app_required CHECK (in_app_enabled)
);

CREATE TABLE notification_destinations (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) NOT NULL REFERENCES application_users(subject) ON DELETE CASCADE,
    channel TEXT NOT NULL CHECK (channel = 'EMAIL'),
    address VARCHAR(254) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_destination_owner_channel UNIQUE (owner_subject, channel),
    CONSTRAINT uq_notification_destination_id_owner UNIQUE (id, owner_subject),
    CONSTRAINT ck_notification_email_address CHECK (
        address = btrim(address) AND address ~ '^[^[:space:]@<>]+@[^[:space:]@<>]+\.[^[:space:]@<>]+$')
);

ALTER TABLE alert_instances ADD CONSTRAINT uq_alert_id_owner UNIQUE (id, owner_subject);

CREATE TABLE alert_deliveries (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES alert_instances(id) ON DELETE CASCADE,
    owner_subject VARCHAR(255) NOT NULL REFERENCES application_users(subject) ON DELETE CASCADE,
    channel TEXT NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL')),
    destination_id UUID REFERENCES notification_destinations(id) ON DELETE CASCADE,
    address_snapshot VARCHAR(254),
    status TEXT NOT NULL CHECK (status IN
        ('DELIVERED', 'PENDING', 'LEASED', 'RETRY_SCHEDULED', 'FAILED', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(100),
    lease_until TIMESTAMPTZ,
    provider_message_id VARCHAR(255),
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uq_alert_delivery_channel UNIQUE (alert_id, channel),
    CONSTRAINT fk_delivery_alert_owner FOREIGN KEY (alert_id, owner_subject)
        REFERENCES alert_instances(id, owner_subject) ON DELETE CASCADE,
    CONSTRAINT fk_delivery_destination_owner FOREIGN KEY (destination_id, owner_subject)
        REFERENCES notification_destinations(id, owner_subject) ON DELETE CASCADE,
    CONSTRAINT ck_alert_delivery_shape CHECK (
        (channel = 'IN_APP' AND destination_id IS NULL AND address_snapshot IS NULL
            AND status = 'DELIVERED'
            AND attempts = 0 AND delivered_at IS NOT NULL)
        OR (channel = 'EMAIL' AND destination_id IS NOT NULL
            AND address_snapshot IS NOT NULL)),
    CONSTRAINT ck_alert_delivery_lease CHECK (
        (status = 'LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'LEASED' AND lease_owner IS NULL AND lease_until IS NULL))
);

CREATE INDEX idx_alert_deliveries_owner_history
    ON alert_deliveries (owner_subject, created_at DESC, id);

CREATE INDEX idx_alert_deliveries_due
    ON alert_deliveries (next_attempt_at, id)
    WHERE channel = 'EMAIL' AND status IN ('PENDING', 'RETRY_SCHEDULED', 'LEASED');

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES alert_deliveries(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 10),
    outcome TEXT NOT NULL CHECK (outcome IN
        ('SENT', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE', 'CANCELLED', 'UNKNOWN_ACCEPTANCE')),
    error_code VARCHAR(64),
    provider_message_id VARCHAR(255),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_delivery_attempt_number UNIQUE (delivery_id, attempt_number)
);

CREATE FUNCTION reject_delivery_attempt_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'delivery_attempts are immutable';
END;
$$;

CREATE TRIGGER trg_delivery_attempts_immutable
    BEFORE UPDATE ON delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_delivery_attempt_mutation();

CREATE TABLE delivery_outbox (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL UNIQUE REFERENCES alert_deliveries(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'LEASED', 'SENT', 'RETRY_SCHEDULED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(100),
    lease_until TIMESTAMPTZ,
    provider_message_id VARCHAR(255),
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_delivery_outbox_lease CHECK (
        (status = 'LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'LEASED' AND lease_owner IS NULL AND lease_until IS NULL))
);

CREATE INDEX idx_delivery_outbox_due ON delivery_outbox (next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED', 'LEASED');

CREATE TABLE delivery_worker_observation (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    dlq_depth BIGINT NOT NULL CHECK (dlq_depth >= 0),
    observed_at TIMESTAMPTZ NOT NULL
);
