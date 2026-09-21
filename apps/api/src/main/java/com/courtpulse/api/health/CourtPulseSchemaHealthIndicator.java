package com.courtpulse.api.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component("courtPulseSchema")
public final class CourtPulseSchemaHealthIndicator implements HealthIndicator {
    private final JdbcClient jdbc;

    public CourtPulseSchemaHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            long tables = jdbc.sql("""
                            SELECT COUNT(*)
                            FROM information_schema.tables
                            WHERE table_schema = 'public'
                              AND table_name IN ('games', 'canonical_events', 'game_checkpoints',
                                                 'alert_instances', 'processed_events', 'outbox')
                            """)
                    .query(Long.class)
                    .single();
            return tables == 6 ? Health.up().build() : Health.down().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }
}
