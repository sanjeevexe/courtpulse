package com.courtpulse.persistence;

import com.courtpulse.domain.alert.RuleType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record NewAlertRule(
        String gameId,
        RuleType type,
        boolean enabled,
        String playerId,
        String teamId,
        Integer pointsThreshold,
        Integer maximumMargin,
        Integer eligiblePeriod,
        Long maximumClockMillisRemaining) {
    /** Fingerprints the immutable creation request, including its original enabled value. */
    public String requestFingerprint() {
        String canonical = String.join("\u001f",
                value(gameId), value(type == null ? null : type.name()), Boolean.toString(enabled),
                value(playerId), value(teamId), value(pointsThreshold), value(maximumMargin),
                value(eligiblePeriod), value(maximumClockMillisRemaining));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String value(Object value) {
        return value == null ? "-" : value.toString().length() + ":" + value;
    }
}
