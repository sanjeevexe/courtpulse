package com.courtpulse.domain.replay;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class StateChecksum {
    private StateChecksum() {}

    static String sha256(GameState state) {
        StringBuilder canonical = new StringBuilder()
                .append("gameId=").append(state.gameId()).append('\n')
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
        state.appliedEventIdentities().stream()
                .map(identity -> identity.source() + ":" + identity.providerEventId() + ":" + identity.revision())
                .sorted()
                .forEach(identity -> canonical.append("identity=").append(identity).append('\n'));
        for (CanonicalEvent event : state.recentEvents()) {
            canonical.append("recent=")
                    .append(event.sequence()).append(':')
                    .append(event.eventId()).append(':')
                    .append(event.type()).append('\n');
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
