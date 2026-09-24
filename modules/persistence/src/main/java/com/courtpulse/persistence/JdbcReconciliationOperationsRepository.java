package com.courtpulse.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Aggregate-only operator visibility; never selects subjects, payloads, or rule parameters. */
public final class JdbcReconciliationOperationsRepository {
    private final JdbcClient jdbc;

    public JdbcReconciliationOperationsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ReconciliationOperations summary(Instant now) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                               count(*) FILTER (WHERE status = 'REBUILDING') AS rebuilding,
                               count(*) FILTER (WHERE status = 'BLOCKED') AS blocked,
                               count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                               count(*) FILTER (WHERE status = 'UNCHANGED') AS unchanged,
                               min(requested_at) FILTER (WHERE status = 'PENDING') AS oldest_pending,
                               (SELECT count(*) FROM reconciliation_attempts
                                WHERE status IN ('BLOCKED', 'RETRIED')) AS failed_attempts
                        FROM game_reconciliations
                        """).query((row, n) -> {
                    OffsetDateTime oldest = row.getObject("oldest_pending", OffsetDateTime.class);
                    return new ReconciliationOperations(row.getLong("pending"),
                            row.getLong("rebuilding"), row.getLong("blocked"),
                            row.getLong("completed"), row.getLong("unchanged"),
                            row.getLong("failed_attempts"), oldest == null ? null
                                    : Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds()));
                }).single();
    }
}
