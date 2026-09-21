package com.courtpulse.query;

import java.util.List;

public record KeysetPage<T>(List<T> items, String nextCursor) {
    public KeysetPage {
        items = List.copyOf(items);
    }
}
