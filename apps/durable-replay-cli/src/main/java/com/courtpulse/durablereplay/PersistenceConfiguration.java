package com.courtpulse.durablereplay;

import com.courtpulse.persistence.DurableGameProcessor;
import com.courtpulse.persistence.DurableReplayCoordinator;
import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.persistence.JdbcFixtureRepository;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import com.courtpulse.persistence.RuleEngineMetrics;
import com.courtpulse.persistence.JdbcInspectionRepository;
import com.courtpulse.persistence.JdbcOutboxRepository;
import com.courtpulse.persistence.JdbcReplayRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {
    @Bean
    Clock operationalClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper persistenceObjectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    JdbcFixtureRepository fixtureRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcFixtureRepository(jdbc, objectMapper);
    }

    @Bean
    JdbcOutboxRepository outboxRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcOutboxRepository(jdbc, objectMapper);
    }

    @Bean
    JdbcGameProcessingRepository gameProcessingRepository(
            JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcGameProcessingRepository(jdbc, objectMapper);
    }

    @Bean
    JdbcAlertRuleRepository alertRuleRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcAlertRuleRepository(jdbc, objectMapper);
    }

    @Bean
    JdbcInspectionRepository inspectionRepository(JdbcClient jdbc) {
        return new JdbcInspectionRepository(jdbc);
    }

    @Bean
    JdbcReplayRunRepository replayRunRepository(JdbcClient jdbc) {
        return new JdbcReplayRunRepository(jdbc);
    }

    @Bean
    FixtureIngestionService fixtureIngestionService(
            JdbcFixtureRepository fixtures,
            JdbcOutboxRepository outbox,
            TransactionTemplate transactions,
            Clock clock) {
        return new FixtureIngestionService(fixtures, outbox, transactions, clock);
    }

    @Bean
    DurableGameProcessor durableGameProcessor(
            JdbcGameProcessingRepository repository,
            JdbcAlertRuleRepository rules,
            JdbcOutboxRepository outbox,
            TransactionTemplate transactions,
            Clock clock) {
        return new DurableGameProcessor(
                repository,
                outbox,
                transactions,
                rules,
                clock,
                RuleEngineMetrics.NONE);
    }

    @Bean
    DurableReplayCoordinator durableReplayCoordinator(
            FixtureIngestionService ingestion,
            JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processing,
            DurableGameProcessor processor,
            JdbcInspectionRepository inspection,
            JdbcReplayRunRepository replayRuns,
            Clock clock) {
        return new DurableReplayCoordinator(
                ingestion, fixtures, processing, processor, inspection, replayRuns, clock);
    }
}
