package com.courtpulse.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcUserOwnershipRepository {
    private final JdbcClient jdbc;

    public JdbcUserOwnershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void touchUser(String subject, Instant now) {
        requireSubject(subject);
        jdbc.sql("""
                        INSERT INTO application_users(subject, created_at, last_seen_at)
                        VALUES (:subject, :now, :now)
                        ON CONFLICT (subject) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                        WHERE application_users.last_seen_at <= EXCLUDED.last_seen_at - INTERVAL '15 minutes'
                        """)
                .param("subject", subject)
                .param("now", SqlTime.offset(now))
                .update();
    }

    public boolean gameExists(String gameId) {
        return jdbc.sql("SELECT count(*) FROM games WHERE id = :gameId")
                .param("gameId", gameId)
                .query(Long.class)
                .single() == 1L;
    }

    public boolean follow(String subject, String gameId, Instant now) {
        requireSubject(subject);
        return jdbc.sql("""
                        INSERT INTO followed_games(user_subject, game_id, followed_at)
                        VALUES (:subject, :gameId, :now)
                        ON CONFLICT (user_subject, game_id) DO NOTHING
                        """)
                .param("subject", subject)
                .param("gameId", gameId)
                .param("now", SqlTime.offset(now))
                .update() == 1;
    }

    public boolean unfollow(String subject, String gameId) {
        requireSubject(subject);
        return jdbc.sql("""
                        DELETE FROM followed_games
                        WHERE user_subject = :subject AND game_id = :gameId
                        """)
                .param("subject", subject)
                .param("gameId", gameId)
                .update() == 1;
    }

    public List<FollowedGameRecord> followedGames(String subject) {
        requireSubject(subject);
        return jdbc.sql("""
                        SELECT game_id, followed_at
                        FROM followed_games
                        WHERE user_subject = :subject
                        ORDER BY followed_at DESC, game_id
                        """)
                .param("subject", subject)
                .query((resultSet, rowNumber) -> new FollowedGameRecord(
                        resultSet.getString("game_id"),
                        resultSet.getObject("followed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static void requireSubject(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("Authenticated subject is invalid");
        }
    }
}
