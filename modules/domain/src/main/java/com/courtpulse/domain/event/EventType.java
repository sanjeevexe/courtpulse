package com.courtpulse.domain.event;

/** Canonical event types. Provider plays that do not change score or status are PLAY_RECORDED. */
public enum EventType {
    GAME_STARTED,
    PERIOD_STARTED,
    FIELD_GOAL_MADE,
    FREE_THROW_MADE,
    PLAY_RECORDED,
    GAME_FINAL
}
