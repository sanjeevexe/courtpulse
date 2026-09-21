UPDATE outbox
SET payload = jsonb_set(payload, '{stateVersion}', to_jsonb((payload ->> 'sequence')::BIGINT))
WHERE destination = 'FUTURE_NOTIFICATIONS'
  AND event_type = 'GAME_STATE_UPDATED'
  AND NOT payload ? 'stateVersion';

UPDATE outbox realtime
SET payload = jsonb_set(realtime.payload, '{stateVersion}', to_jsonb(event.sequence_number))
FROM alert_instances alert
JOIN canonical_events event ON event.event_id = alert.triggering_event_id
WHERE realtime.destination = 'FUTURE_NOTIFICATIONS'
  AND realtime.event_type = 'ALERT_CREATED'
  AND realtime.payload ->> 'triggerKey' = alert.trigger_key
  AND NOT realtime.payload ? 'stateVersion';

CREATE INDEX idx_outbox_realtime_publishable
    ON outbox (destination, status, next_attempt_at, message_group_id, created_at, id)
    WHERE destination = 'FUTURE_NOTIFICATIONS'
      AND status IN ('PENDING', 'RETRY_SCHEDULED', 'PUBLISHING');

CREATE INDEX idx_outbox_realtime_group_order
    ON outbox (message_group_id, created_at, id)
    WHERE destination = 'FUTURE_NOTIFICATIONS';
