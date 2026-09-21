package com.courtpulse.api.http;

import com.courtpulse.query.GameSnapshotReadModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class SnapshotEtag {
    private SnapshotEtag() {}

    static String of(GameSnapshotReadModel snapshot) {
        String material = snapshot.gameId() + ':' + snapshot.stateVersion() + ':' + snapshot.stateChecksum();
        try {
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
            return "\"cp-" + digest + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
