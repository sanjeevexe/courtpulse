package com.courtpulse.api.http;

import com.courtpulse.query.GameSnapshotReadModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;

final class SnapshotEtag {
    private SnapshotEtag() {}

    /**
     * Covers durable state plus everything else a cached body shows: data status (so a 304 can
     * never keep presenting a stale game as live) and provider display names resolved later.
     */
    static String of(GameSnapshotReadModel snapshot) {
        String material = snapshot.gameId() + ':' + snapshot.stateVersion() + ':' + snapshot.stateChecksum()
                + ':' + snapshot.dataStatus() + ':' + Objects.toString(snapshot.teams(), "")
                + ':' + Objects.toString(snapshot.scheduledAt(), "")
                + ':' + (snapshot.playerNames() == null ? "" : new TreeMap<>(snapshot.playerNames()));
        try {
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
            return "\"cp-" + digest + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
