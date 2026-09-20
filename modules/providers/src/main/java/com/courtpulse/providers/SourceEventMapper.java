package com.courtpulse.providers;

import com.courtpulse.domain.event.CanonicalEvent;

/** Boundary implemented by every provider or fixture adapter. */
@FunctionalInterface
public interface SourceEventMapper<T> {
    CanonicalEvent toCanonicalEvent(T sourceEvent);
}
