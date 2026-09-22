package com.courtpulse.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Matches PostgreSQL's raw {@code md5(text)::uuid} backfill identity. */
public final class SystemDemoRuleId {
    private SystemDemoRuleId() {}

    public static UUID forGame(String gameId) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(
                    ("system-rule:" + gameId + ":player_ace:10")
                            .getBytes(StandardCharsets.UTF_8)));
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
                    + hex.substring(20));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }
}
