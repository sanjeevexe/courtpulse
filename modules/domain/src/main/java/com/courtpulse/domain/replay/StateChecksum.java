package com.courtpulse.domain.replay;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over the versioned complete durable game-state representation.
 * Operational counters and timestamps are intentionally excluded.
 */
public final class StateChecksum {
    public static final int REPRESENTATION_VERSION = 2;

    private StateChecksum() {}

    public static String sha256(GameState state) {
        StringBuilder canonical = new StringBuilder()
                .append("stateChecksumVersion=").append(REPRESENTATION_VERSION).append('\n')
                .append("gameId=").append(state.gameId()).append('\n')
                .append("homeTeamId=").append(state.homeTeamId()).append('\n')
                .append("awayTeamId=").append(state.awayTeamId()).append('\n')
                .append("status=").append(state.status()).append('\n')
                .append("period=").append(state.period()).append('\n')
                .append("clockMillisRemaining=").append(state.clockMillisRemaining()).append('\n')
                .append("homeScore=").append(state.homeScore()).append('\n')
                .append("awayScore=").append(state.awayScore()).append('\n')
                .append("lastAppliedSequence=").append(state.lastAppliedSequence()).append('\n');

        state.playerPoints().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append("playerPoints.")
                        .append(entry.getKey()).append('=')
                        .append(entry.getValue()).append('\n'));
        state.appliedEventFingerprints().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(identity -> identity.source()
                                + ":" + identity.providerEventId()
                                + ":" + identity.revision())))
                .forEach(entry -> canonical.append("identity=")
                        .append(entry.getKey().source()).append(':')
                        .append(entry.getKey().providerEventId()).append(':')
                        .append(entry.getKey().revision()).append(':')
                        .append(entry.getValue()).append('\n'));
        for (CanonicalEvent event : state.recentEvents()) {
            canonical.append("recent=")
                    .append(event.sequence()).append(':')
                    .append(event.eventId()).append(':')
                    .append(com.courtpulse.domain.event.EventFingerprint.sha256(event)).append('\n');
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
