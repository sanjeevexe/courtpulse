package com.courtpulse.api;

import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.persistence.DurableGameProcessor;
import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.persistence.JdbcFixtureRepository;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.JdbcOutboxRepository;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class ApiTestData {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T16:00:00Z"), ZoneOffset.UTC);

    private ApiTestData() {}

    static SeededServices seed(DataSource dataSource, boolean processAll) {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcFixtureRepository fixtures = new JdbcFixtureRepository(jdbc, mapper);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, mapper);
        JdbcGameProcessingRepository processing = new JdbcGameProcessingRepository(jdbc, mapper);
        FixtureIngestionService ingestion = new FixtureIngestionService(fixtures, outbox, transactions, CLOCK);
        DurableGameProcessor processor = new DurableGameProcessor(
                processing,
                outbox,
                transactions,
                List.of(new PlayerMilestoneRule("milestone-player-ace-10", "player_ace", 10)),
                CLOCK);
        fixtures.resetAll();
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        ingestion.importFixture(fixture);
        if (processAll) {
            processing.listEventIds(fixture.game().gameId()).forEach(processor::processEvent);
            jdbc.sql("""
                            UPDATE outbox
                            SET status = 'SENT', published_at = :now
                            WHERE destination = 'GAME_EVENTS'
                            """)
                    .param("now", CLOCK.instant().atOffset(ZoneOffset.UTC))
                    .update();
        }
        return new SeededServices(jdbc, fixtures, processing, processor, fixture);
    }

    record SeededServices(
            JdbcClient jdbc,
            JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processing,
            DurableGameProcessor processor,
            LoadedFixture fixture) {}
}
