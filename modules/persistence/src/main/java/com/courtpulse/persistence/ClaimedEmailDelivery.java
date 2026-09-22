package com.courtpulse.persistence;

import java.util.UUID;

public record ClaimedEmailDelivery(
        UUID id,
        String address,
        String title,
        String gameId,
        boolean optedIn,
        int attempt) {}
