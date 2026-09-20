package com.courtpulse.domain.event;

/** Event types supported by the first deterministic replay milestone. */
public enum EventType {
    GAME_STARTED,
    PERIOD_STARTED,
    FIELD_GOAL_MADE,
    FREE_THROW_MADE,
    GAME_FINAL
}
