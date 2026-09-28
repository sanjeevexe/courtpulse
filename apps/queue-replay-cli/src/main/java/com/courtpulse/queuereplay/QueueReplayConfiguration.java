package com.courtpulse.queuereplay;

import com.courtpulse.messaging.consumer.CanonicalEventEnvelopeValidator;
import com.courtpulse.messaging.consumer.GameEventQueueConsumer;
import com.courtpulse.messaging.publisher.OutboxPublisher;
import com.courtpulse.messaging.publisher.PublicationRetryPolicy;
import com.courtpulse.messaging.queue.GameEventEnvelopeCodec;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.sqs.SqsQueueAdapter;
import com.courtpulse.messaging.delivery.DeliveryQueuePublisher;
import com.courtpulse.messaging.delivery.DeliveryQueueConsumer;
import com.courtpulse.messaging.delivery.EmailSender;
import com.courtpulse.messaging.delivery.LocalSmtpEmailSender;
import com.courtpulse.messaging.delivery.SesEmailSender;
import software.amazon.awssdk.services.ses.SesClient;
import com.courtpulse.persistence.JdbcDeliveryWorkRepository;
import com.courtpulse.persistence.JdbcOperationalTelemetryRepository;
import com.courtpulse.persistence.DurableGameProcessor;
import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.persistence.GameReconciliationService;
import com.courtpulse.persistence.JdbcFixtureRepository;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import com.courtpulse.persistence.RuleEngineMetrics;
import com.courtpulse.persistence.MicrometerRuleEngineMetrics;
import com.courtpulse.persistence.JdbcInspectionRepository;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.JdbcOutboxRepository;
import com.courtpulse.persistence.JdbcProviderRepository;
import com.courtpulse.persistence.ProviderIngestionService;
import com.courtpulse.providers.balldontlie.BallDontLieProvider;
import com.courtpulse.providers.live.LiveGameProvider;
import com.courtpulse.providers.live.ProviderCircuitBreaker;
import com.courtpulse.providers.live.ProviderRateLimiter;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
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
    JdbcDeliveryWorkRepository deliveryWorkRepository(JdbcClient jdbc) {
        return new JdbcDeliveryWorkRepository(jdbc);
    }

    @Bean
    JdbcOperationalTelemetryRepository operationalTelemetryRepository(JdbcClient jdbc) {
        return new JdbcOperationalTelemetryRepository(jdbc);
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
    GameReconciliationService gameReconciliationService(
            JdbcClient jdbc, JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processing, JdbcOutboxRepository outbox,
            JdbcAlertRuleRepository rules, TransactionTemplate transactions, Clock clock,
            ObjectMapper mapper) {
        return new GameReconciliationService(
                jdbc, fixtures, processing, outbox, rules, transactions, clock, mapper);
    }

    @Bean
    JdbcProviderRepository providerRepository(JdbcClient jdbc) {
        return new JdbcProviderRepository(jdbc);
    }

    @Bean
    ProviderIngestionService providerIngestionService(
            JdbcProviderRepository providers, JdbcFixtureRepository fixtures, JdbcOutboxRepository outbox,
            GameReconciliationService reconciliation, TransactionTemplate transactions, Clock clock,
            @Value("${courtpulse.ingest.final-refetch-window}") Duration finalRefetchWindow,
            @Value("${courtpulse.ingest.final-refetch-interval}") Duration finalRefetchInterval) {
        return new ProviderIngestionService(providers, fixtures, outbox, reconciliation, transactions, clock,
                new ProviderIngestionService.FinalRefetch(finalRefetchWindow, finalRefetchInterval));
    }

    /** Created only when explicitly selected; no provider credential is needed otherwise. */
    @Bean
    @ConditionalOnProperty(name = "courtpulse.provider.name", havingValue = "balldontlie")
    LiveGameProvider ballDontLieProvider(
            ObjectMapper mapper, Clock clock,
            @Value("${courtpulse.provider.base-url}") URI baseUrl,
            @Value("${courtpulse.provider.api-key:}") String apiKey,
            @Value("${courtpulse.provider.request-timeout}") Duration requestTimeout,
            @Value("${courtpulse.provider.requests-per-minute}") int requestsPerMinute,
            @Value("${courtpulse.provider.team-ids:}") String teamIds) {
        Set<String> teams = Arrays.stream(teamIds.split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).collect(Collectors.toSet());
        return new BallDontLieProvider(
                new BallDontLieProvider.Settings(baseUrl, apiKey, requestTimeout, 8 * 1024 * 1024, teams),
                // HTTP/1.1 avoids the cleartext h2c upgrade against a local simulator; the API is tiny.
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                mapper,
                new ProviderRateLimiter(requestsPerMinute, Duration.ofSeconds(10), clock, Thread::sleep),
                new ProviderCircuitBreaker(5, Duration.ofSeconds(60), clock),
                clock);
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
        // "aws" (or blank) selects the SDK's regional endpoint and default credential chain.
        if (!endpoint.isBlank() && !"aws".equalsIgnoreCase(endpoint)) {
            URI endpointUri = URI.create(endpoint);
            String host = endpointUri.getHost();
            if (!("localhost".equals(host) || "127.0.0.1".equals(host)
                    || "localstack".equals(host))) {
                throw new IllegalArgumentException(
                        "SQS endpoint overrides and dummy credentials are restricted to localstack");
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

    @Bean("alertDeliveriesQueue")
    QueuePort alertDeliveriesQueue(
            SqsClient client, @Value("${courtpulse.sqs.alert-deliveries-queue}") String name) {
        return new SqsQueueAdapter(client,
                client.getQueueUrl(builder -> builder.queueName(name)).queueUrl());
    }

    @Bean("alertDeliveriesDlq")
    QueuePort alertDeliveriesDlq(
            SqsClient client, @Value("${courtpulse.sqs.alert-deliveries-dlq}") String name) {
        return new SqsQueueAdapter(client,
                client.getQueueUrl(builder -> builder.queueName(name)).queueUrl());
    }

    @Bean
    MeterBinder deliveryDlqMeter(@Qualifier("alertDeliveriesDlq") QueuePort dlq) {
        return registry -> Gauge.builder("courtpulse.delivery.dlq.depth", dlq,
                queue -> queue.depth().total()).register(registry);
    }

    /** Mailpit locally; Amazon SES (task-role credentials, verified sender) when deployed. */
    @Bean
    EmailSender emailSender(
            @Value("${courtpulse.email.provider}") String provider,
            @Value("${courtpulse.mailpit.host}") String host,
            @Value("${courtpulse.mailpit.port}") int port,
            @Value("${courtpulse.email.from}") String from,
            @Value("${courtpulse.email.ses-configuration-set:}") String configurationSet,
            @Value("${courtpulse.sqs.region}") String region,
            @Value("${courtpulse.email.ses-endpoint:}") String sesEndpoint,
            @Value("${courtpulse.sqs.local-access-key:test}") String accessKey,
            @Value("${courtpulse.sqs.local-secret-key:test}") String secretKey) {
        return switch (provider) {
            case "mailpit" -> new LocalSmtpEmailSender(host, port);
            case "ses" -> {
                var builder = SesClient.builder().region(Region.of(region));
                if (!sesEndpoint.isBlank()) {
                    URI endpointUri = URI.create(sesEndpoint);
                    if (!("localhost".equals(endpointUri.getHost()) || "127.0.0.1".equals(endpointUri.getHost())
                            || "localstack".equals(endpointUri.getHost()))) {
                        throw new IllegalArgumentException(
                                "SES endpoint overrides and dummy credentials are restricted to localstack");
                    }
                    builder.endpointOverride(endpointUri).credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
                }
                yield new SesEmailSender(builder.build(), from, configurationSet);
            }
            default -> throw new IllegalArgumentException("COURTPULSE_EMAIL_PROVIDER must be mailpit or ses");
        };
    }

    @Bean
    DeliveryQueuePublisher deliveryQueuePublisher(
            JdbcDeliveryWorkRepository work,
            @Qualifier("alertDeliveriesQueue") QueuePort queue,
            TransactionTemplate transactions, Clock clock) {
        return new DeliveryQueuePublisher(
                work, queue, transactions, clock, "delivery-publisher-" + UUID.randomUUID());
    }

    @Bean
    DeliveryQueueConsumer deliveryQueueConsumer(
            JdbcDeliveryWorkRepository work,
            @Qualifier("alertDeliveriesQueue") QueuePort queue,
            EmailSender email, TransactionTemplate transactions, Clock clock,
            @Value("${courtpulse.email.public-base-url:}") String publicBaseUrl) {
        return new DeliveryQueueConsumer(
                work, queue, email, transactions, clock,
                "delivery-consumer-" + UUID.randomUUID(), publicBaseUrl);
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
            JdbcOperationalTelemetryRepository operationalTelemetry,
            DurableGameProcessor processor,
            @Value("${courtpulse.consumer.batch-size}") int batchSize,
            @Value("${courtpulse.consumer.long-poll-wait}") Duration waitTime) {
        return new GameEventQueueConsumer(
                queue,
                codec,
                new CanonicalEventEnvelopeValidator(processing, outbox),
                processor,
                batchSize,
                waitTime,
                operationalTelemetry);
    }
}
