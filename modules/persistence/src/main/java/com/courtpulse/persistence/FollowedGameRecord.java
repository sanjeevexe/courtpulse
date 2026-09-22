package com.courtpulse.persistence;

import java.time.Instant;

public record FollowedGameRecord(String gameId, Instant followedAt) {}
