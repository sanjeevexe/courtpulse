package com.courtpulse.queuereplay;

import com.courtpulse.messaging.consumer.CanonicalEventEnvelopeValidator;
import com.courtpulse.messaging.consumer.GameEventQueueConsumer;
import com.courtpulse.messaging.publisher.OutboxPublisher;
import com.courtpulse.messaging.publisher.PublicationRetryPolicy;
import com.courtpulse.messaging.queue.GameEventEnvelopeCodec;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.sqs.SqsQueueAdapter;
import com.courtpulse.persistence.DurableGameProcessor;
import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.persistence.JdbcFixtureRepository;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import com.courtpulse.persistence.RuleEngineMetrics;
import com.courtpulse.persistence.MicrometerRuleEngineMetrics;
import com.courtpulse.persistence.JdbcInspectionRepository;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.JdbcOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
public class QueueReplayConfiguration {
    @Bean
    Clock operationalClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper queueObjectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    JdbcFixtureRepository fixtureRepository(JdbcClient jdbc, ObjectMapper mapper) {
        return new JdbcFixtureRepository(jdbc, mapper);
    }

    @Bean
    JdbcOutboxRepository outboxRepository(JdbcClient jdbc, ObjectMapper mapper) {
        return new JdbcOutboxRepository(jdbc, mapper);
    }

    @Bean
    JdbcOutboxPublicationRepository outboxPublicationRepository(JdbcClient jdbc) {
        return new JdbcOutboxPublicationRepository(jdbc);
    }

    @Bean
    JdbcGameProcessingRepository gameProcessingRepository(JdbcClient jdbc, ObjectMapper mapper) {
        return new JdbcGameProcessingRepository(jdbc, mapper);
    }

    @Bean
    JdbcAlertRuleRepository alertRuleRepository(JdbcClient jdbc, ObjectMapper mapper) {
        return new JdbcAlertRuleRepository(jdbc, mapper);
    }

    @Bean
    RuleEngineMetrics ruleEngineMetrics(MeterRegistry registry) {
        return new MicrometerRuleEngineMetrics(registry);
    }

    @Bean
    JdbcInspectionRepository inspectionRepository(JdbcClient jdbc) {
        return new JdbcInspectionRepository(jdbc);
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
            JdbcGameProcessingRepository processing,
            JdbcAlertRuleRepository rules,
            JdbcOutboxRepository outbox,
            TransactionTemplate transactions,
            Clock clock,
            RuleEngineMetrics metrics) {
        return new DurableGameProcessor(
                processing,
                outbox,
                transactions,
                rules,
                clock,
                metrics);
    }

    @Bean
    GameEventEnvelopeCodec envelopeCodec(ObjectMapper mapper) {
        return new GameEventEnvelopeCodec(mapper);
    }

    @Bean
    SqsClient sqsClient(
            @Value("${courtpulse.sqs.region}") String region,
            @Value("${courtpulse.sqs.endpoint:}") String endpoint,
            @Value("${courtpulse.sqs.local-access-key:test}") String accessKey,
            @Value("${courtpulse.sqs.local-secret-key:test}") String secretKey) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            URI endpointUri = URI.create(endpoint);
            String host = endpointUri.getHost();
            if (!("localhost".equals(host) || "127.0.0.1".equals(host))) {
                throw new IllegalArgumentException(
                        "SQS endpoint overrides and dummy credentials are restricted to localhost");
            }
            builder.endpointOverride(endpointUri);
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    @Bean("gameEventsQueueUrl")
    String gameEventsQueueUrl(
            SqsClient client, @Value("${courtpulse.sqs.game-events-queue}") String name) {
        return client.getQueueUrl(builder -> builder.queueName(name)).queueUrl();
    }

    @Bean("gameEventsDlqUrl")
    String gameEventsDlqUrl(
            SqsClient client, @Value("${courtpulse.sqs.game-events-dlq}") String name) {
        return client.getQueueUrl(builder -> builder.queueName(name)).queueUrl();
    }

    @Bean("gameEventsQueue")
    QueuePort gameEventsQueue(
            SqsClient client, @Qualifier("gameEventsQueueUrl") String queueUrl) {
        return new SqsQueueAdapter(client, queueUrl);
    }

    @Bean("gameEventsDlq")
    QueuePort gameEventsDlq(
            SqsClient client, @Qualifier("gameEventsDlqUrl") String queueUrl) {
        return new SqsQueueAdapter(client, queueUrl);
    }

    @Bean
    OutboxPublisher outboxPublisher(
            JdbcOutboxPublicationRepository outbox,
            @Qualifier("gameEventsQueue") QueuePort queue,
            GameEventEnvelopeCodec codec,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${courtpulse.publisher.lease-duration}") Duration leaseDuration,
            @Value("${courtpulse.publisher.batch-size}") int batchSize,
            @Value("${courtpulse.publisher.retry-initial}") Duration retryInitial,
            @Value("${courtpulse.publisher.retry-maximum}") Duration retryMaximum,
            @Value("${courtpulse.publisher.maximum-attempts}") int maximumAttempts) {
        return new OutboxPublisher(
                outbox,
                queue,
                codec,
                transactions,
                new PublicationRetryPolicy(retryInitial, retryMaximum, maximumAttempts, Math::random),
                clock,
                "queue-replay-" + UUID.randomUUID(),
                leaseDuration,
                batchSize);
    }

    @Bean
    GameEventQueueConsumer gameEventQueueConsumer(
            @Qualifier("gameEventsQueue") QueuePort queue,
            GameEventEnvelopeCodec codec,
            JdbcGameProcessingRepository processing,
            JdbcOutboxPublicationRepository outbox,
            DurableGameProcessor processor,
            @Value("${courtpulse.consumer.batch-size}") int batchSize,
            @Value("${courtpulse.consumer.long-poll-wait}") Duration waitTime) {
        return new GameEventQueueConsumer(
                queue,
                codec,
                new CanonicalEventEnvelopeValidator(processing, outbox),
                processor,
                batchSize,
                waitTime);
    }
}
