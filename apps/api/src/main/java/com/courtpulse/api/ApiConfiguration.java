package com.courtpulse.api;

import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.api.realtime.RealtimeHub;
import com.courtpulse.api.realtime.RealtimeOutboxPublisher;
import com.courtpulse.api.realtime.RealtimeProtocol;
import com.courtpulse.api.realtime.RealtimeKeepAliveScheduler;
import com.courtpulse.api.realtime.RealtimePublicationScheduler;
import com.courtpulse.api.realtime.RealtimeWebSocketHandler;
import com.courtpulse.query.DataStatusPolicy;
import com.courtpulse.query.JdbcCourtPulseReadRepository;
import com.courtpulse.query.OpaqueCursorCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.JdbcUserOwnershipRepository;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import com.courtpulse.persistence.JdbcAlertDeliveryRepository;
import com.courtpulse.persistence.JdbcDeliveryWorkRepository;
import com.courtpulse.persistence.JdbcProviderRepository;
import com.courtpulse.persistence.JdbcReconciliationOperationsRepository;
import com.courtpulse.api.notifications.NotificationService;
import com.courtpulse.api.operations.OperationalMetrics;
import com.courtpulse.persistence.MicrometerRuleEngineMetrics;
import com.courtpulse.persistence.RuleEngineMetrics;
import com.courtpulse.api.ownership.OwnershipService;
import com.courtpulse.api.rules.PersonalizedRuleService;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration(proxyBeanMethods = false)
public class ApiConfiguration {
    @Bean
    Clock apiClock() {
        return Clock.systemUTC();
    }

    @Bean("queryObjectMapper")
    ObjectMapper queryObjectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    @Bean
    DataStatusPolicy dataStatusPolicy(
            Clock clock,
            @Value("${courtpulse.api.live-freshness-window}") Duration freshnessWindow) {
        return new DataStatusPolicy(clock, freshnessWindow);
    }

    @Bean
    JdbcCourtPulseReadRepository courtPulseReadRepository(
            JdbcClient jdbc,
            @Qualifier("queryObjectMapper") ObjectMapper objectMapper,
            DataStatusPolicy dataStatusPolicy,
            Clock clock) {
        return new JdbcCourtPulseReadRepository(jdbc, objectMapper, dataStatusPolicy, clock);
    }

    @Bean
    OpaqueCursorCodec opaqueCursorCodec(
            @Qualifier("queryObjectMapper") ObjectMapper objectMapper) {
        return new OpaqueCursorCodec(objectMapper);
    }

    @Bean
    CourtPulseQueryService courtPulseQueryService(
            JdbcCourtPulseReadRepository repository, OpaqueCursorCodec cursors) {
        return new CourtPulseQueryService(repository, cursors);
    }

    @Bean
    TransactionTemplate apiTransactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    JdbcOutboxPublicationRepository realtimeOutboxRepository(JdbcClient jdbc) {
        return new JdbcOutboxPublicationRepository(jdbc);
    }

    @Bean
    JdbcUserOwnershipRepository userOwnershipRepository(JdbcClient jdbc) {
        return new JdbcUserOwnershipRepository(jdbc);
    }

    @Bean
    JdbcAlertRuleRepository alertRuleRepository(
            JdbcClient jdbc, @Qualifier("queryObjectMapper") ObjectMapper objectMapper) {
        return new JdbcAlertRuleRepository(jdbc, objectMapper);
    }

    @Bean
    JdbcAlertDeliveryRepository alertDeliveryRepository(JdbcClient jdbc) {
        return new JdbcAlertDeliveryRepository(jdbc);
    }

    @Bean
    JdbcDeliveryWorkRepository deliveryWorkRepository(JdbcClient jdbc) {
        return new JdbcDeliveryWorkRepository(jdbc);
    }

    @Bean
    JdbcReconciliationOperationsRepository reconciliationOperationsRepository(JdbcClient jdbc) {
        return new JdbcReconciliationOperationsRepository(jdbc);
    }

    @Bean
    JdbcProviderRepository providerRepository(JdbcClient jdbc) {
        return new JdbcProviderRepository(jdbc);
    }

    @Bean
    MeterBinder deliveryAggregateMeters(JdbcDeliveryWorkRepository deliveries, Clock clock) {
        return registry -> {
            Gauge.builder("courtpulse.delivery.backlog", deliveries,
                    work -> work.operations(clock.instant()).backlog()).register(registry);
            Gauge.builder("courtpulse.delivery.oldest.pending.seconds", deliveries,
                    work -> work.operations(clock.instant()).oldestPendingAgeSeconds()).register(registry);
            Gauge.builder("courtpulse.delivery.publications", deliveries,
                    work -> work.operations(clock.instant()).publications()).register(registry);
            Gauge.builder("courtpulse.delivery.attempts", deliveries,
                    work -> work.operations(clock.instant()).attempts()).register(registry);
            Gauge.builder("courtpulse.delivery.successes", deliveries,
                    work -> work.operations(clock.instant()).successes()).register(registry);
            Gauge.builder("courtpulse.delivery.retries", deliveries,
                    work -> work.operations(clock.instant()).retries()).register(registry);
            Gauge.builder("courtpulse.delivery.terminal.failures", deliveries,
                    work -> work.operations(clock.instant()).terminalFailures()).register(registry);
            Gauge.builder("courtpulse.delivery.lease.recoveries", deliveries,
                    work -> work.operations(clock.instant()).leaseRecoveries()).register(registry);
            Gauge.builder("courtpulse.delivery.dlq.depth", deliveries,
                    work -> work.operations(clock.instant()).dlqDepth()).register(registry);
        };
    }

    @Bean
    MeterBinder operationalMeters(JdbcClient jdbc, Clock clock) {
        return new OperationalMetrics(jdbc, clock);
    }

    @Bean
    NotificationService notificationService(
            JdbcAlertDeliveryRepository deliveries,
            JdbcUserOwnershipRepository users,
            TransactionTemplate transactions,
            Clock clock,
            OpaqueCursorCodec cursors) {
        return new NotificationService(deliveries, users, transactions, clock, cursors);
    }

    @Bean
    RuleEngineMetrics ruleEngineMetrics(MeterRegistry meters) {
        return new MicrometerRuleEngineMetrics(meters);
    }

    @Bean
    PersonalizedRuleService personalizedRuleService(
            JdbcAlertRuleRepository rules,
            JdbcUserOwnershipRepository users,
            OpaqueCursorCodec cursors,
            TransactionTemplate transactions,
            Clock clock,
            RuleEngineMetrics metrics) {
        return new PersonalizedRuleService(rules, users, cursors, transactions, clock, metrics);
    }

    @Bean
    OwnershipService ownershipService(
            JdbcUserOwnershipRepository repository,
            TransactionTemplate transactions,
            Clock clock) {
        return new OwnershipService(repository, transactions, clock);
    }

    @Bean("realtimeLeaseOwner")
    String realtimeLeaseOwner() {
        return "api-realtime-" + UUID.randomUUID();
    }

    @Bean
    RealtimeProtocol realtimeProtocol(@Qualifier("queryObjectMapper") ObjectMapper objectMapper) {
        return new RealtimeProtocol(objectMapper);
    }

    @Bean
    RealtimeHub realtimeHub(
            RealtimeProtocol protocol,
            CourtPulseQueryService queries,
            Clock clock,
            MeterRegistry meters,
            @Value("${courtpulse.realtime.session-limit}") int sessionLimit,
            @Value("${courtpulse.realtime.outbound-buffer-size}") int outboundBufferSize) {
        return new RealtimeHub(
                protocol, queries, clock, meters, sessionLimit, outboundBufferSize);
    }

    @Bean
    RealtimeWebSocketHandler realtimeWebSocketHandler(RealtimeHub hub) {
        return new RealtimeWebSocketHandler(hub);
    }

    @Bean
    RealtimeOutboxPublisher realtimeOutboxPublisher(
            JdbcOutboxPublicationRepository repository,
            RealtimeHub hub,
            TransactionTemplate transactions,
            Clock clock,
            @Qualifier("realtimeLeaseOwner") String leaseOwner,
            @Value("${courtpulse.realtime.lease-duration}") Duration leaseDuration,
            @Value("${courtpulse.realtime.batch-size}") int batchSize,
            @Value("${courtpulse.realtime.maximum-attempts}") int maximumAttempts,
            MeterRegistry meters) {
        return new RealtimeOutboxPublisher(
                repository,
                hub,
                transactions,
                clock,
                leaseOwner,
                leaseDuration,
                batchSize,
                maximumAttempts,
                meters);
    }

    @Bean
    @ConditionalOnProperty(
            name = "courtpulse.realtime.publication.enabled",
            havingValue = "true",
            matchIfMissing = true)
    RealtimePublicationScheduler realtimePublicationScheduler(RealtimeOutboxPublisher publisher) {
        return new RealtimePublicationScheduler(publisher);
    }

    /** Runs just after Spring Security so authenticated callers are limited by JWT subject. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "courtpulse.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    org.springframework.boot.web.servlet.FilterRegistrationBean<com.courtpulse.api.http.RateLimitFilter> rateLimitFilter(
            com.courtpulse.api.security.SecurityProblemWriter problems,
            MeterRegistry meters,
            @Value("${courtpulse.rate-limit.public-reads-per-minute}") int publicReads,
            @Value("${courtpulse.rate-limit.owner-reads-per-minute}") int ownerReads,
            @Value("${courtpulse.rate-limit.owner-writes-per-minute}") int ownerWrites,
            @Value("${courtpulse.rate-limit.operations-per-minute}") int operations,
            @Value("${courtpulse.rate-limit.realtime-handshakes-per-minute}") int handshakes,
            @Value("${courtpulse.rate-limit.maximum-tracked-clients}") int maximumTrackedClients) {
        var limits = new java.util.EnumMap<com.courtpulse.api.http.RateLimitFilter.EndpointClass, Integer>(
                com.courtpulse.api.http.RateLimitFilter.EndpointClass.class);
        limits.put(com.courtpulse.api.http.RateLimitFilter.EndpointClass.PUBLIC_READ, publicReads);
        limits.put(com.courtpulse.api.http.RateLimitFilter.EndpointClass.OWNER_READ, ownerReads);
        limits.put(com.courtpulse.api.http.RateLimitFilter.EndpointClass.OWNER_WRITE, ownerWrites);
        limits.put(com.courtpulse.api.http.RateLimitFilter.EndpointClass.OPERATIONS, operations);
        limits.put(com.courtpulse.api.http.RateLimitFilter.EndpointClass.REALTIME_HANDSHAKE, handshakes);
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new com.courtpulse.api.http.RateLimitFilter(
                        limits, problems, meters, System::nanoTime, maximumTrackedClients));
        registration.setOrder(org.springframework.boot.security.autoconfigure.web.servlet
                .SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10);
        return registration;
    }

    @Bean
    RealtimeKeepAliveScheduler realtimeKeepAliveScheduler(RealtimeHub hub) {
        return new RealtimeKeepAliveScheduler(hub);
    }

    @Bean
    OpenAPI courtPulseOpenApi() {
        return new OpenAPI().info(new Info()
                .title("CourtPulse Read API")
                .version("1.0.0")
                .description("Read-only durable game, event, alert, and processing state."));
    }

    @Bean
    OpenApiCustomizer correlationHeaderCustomizer() {
        return openApi -> openApi.getPaths().values().forEach(path -> {
            path.readOperations().forEach(operation -> {
                addStandardError(operation.getResponses(), "405", "HTTP method is not supported");
                addStandardError(operation.getResponses(), "406", "Requested representation is unavailable");
                addStandardError(operation.getResponses(), "429", "Rate limit or quota exceeded");
                addStandardError(operation.getResponses(), "500", "Unexpected server failure");
                addStandardError(operation.getResponses(), "503", "Durable data is unavailable");
                operation.getResponses().values().forEach(response -> response.addHeaderObject(
                        "X-Correlation-ID",
                        new Header().description("Safe request correlation identifier")
                                .schema(new StringSchema())));
                operation.getResponses().forEach((status, response) -> {
                    if (status.matches("4\\d\\d|5\\d\\d")) {
                        response.setContent(new io.swagger.v3.oas.models.media.Content()
                                .addMediaType(
                                        org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                        new io.swagger.v3.oas.models.media.MediaType().schema(
                                                new io.swagger.v3.oas.models.media.Schema<>()
                                                        .$ref("#/components/schemas/Problem"))));
                    }
                    if ("429".equals(status)) {
                        response.addHeaderObject("Retry-After", new Header()
                                .description("Seconds to wait before retrying")
                                .schema(new io.swagger.v3.oas.models.media.IntegerSchema()));
                    }
                    if ("304".equals(status)) {
                        response.setContent(null);
                        response.addHeaderObject(
                                "ETag",
                                new Header().description("Snapshot version validator")
                                        .schema(new StringSchema()));
                    }
                });
            });
        });
    }

    /** Springdoc cannot infer nullable keyset fields or fixed delivery states from repository records. */
    @Bean
    OpenApiCustomizer deliverySchemaCustomizer() {
        return openApi -> {
            var schemas = openApi.getComponents().getSchemas();
            required(schemas, "NotificationSettings", "inAppEnabled", "emailEnabled");
            required(schemas, "DeliveryHistoryPage", "items");
            required(schemas, "DeliveryHistoryRecord", "id", "alertId", "channel", "status",
                    "attempts", "createdAt");
            required(schemas, "DeliveryAttemptPage", "items");
            required(schemas, "DeliveryAttemptRecord", "id", "attemptNumber", "outcome",
                    "completedAt");
            required(schemas, "DeliveryOperations", "backlog", "oldestPendingAgeSeconds",
                    "publications", "attempts", "successes", "retries", "terminalFailures",
                    "leaseRecoveries", "dlqDepth", "dlqObservedAt");
            nullable(schemas, "NotificationSettings", "emailAddress");
            nullable(schemas, "UpdateNotificationSettings", "emailAddress");
            nullable(schemas, "DeliveryHistoryPage", "nextCursor");
            nullable(schemas, "DeliveryHistoryRecord", "deliveredAt", "nextAttemptAt",
                    "lastErrorCode");
            nullable(schemas, "DeliveryAttemptPage", "nextCursor");
            nullable(schemas, "DeliveryAttemptRecord", "errorCode");
            nullable(schemas, "DeliveryOperations", "dlqObservedAt");
            fixedValues(schemas, "DeliveryHistoryRecord", "channel", "IN_APP", "EMAIL");
            fixedValues(schemas, "DeliveryHistoryRecord", "status", "DELIVERED", "PENDING",
                    "LEASED", "RETRY_SCHEDULED", "FAILED", "CANCELLED");
            fixedValues(schemas, "DeliveryAttemptRecord", "outcome", "SENT",
                    "TRANSIENT_FAILURE", "PERMANENT_FAILURE", "CANCELLED", "UNKNOWN_ACCEPTANCE");
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void required(
            java.util.Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
            String name, String... fields) {
        schemas.get(name).setRequired(List.of(fields));
    }

    @SuppressWarnings("rawtypes")
    private static void nullable(
            java.util.Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
            String name, String... fields) {
        for (String field : fields) {
            var property = (io.swagger.v3.oas.models.media.Schema<?>)
                    schemas.get(name).getProperties().get(field);
            property.setType(null);
            property.setTypes(Set.of("string", "null"));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void fixedValues(
            java.util.Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
            String name, String field, String... values) {
        ((io.swagger.v3.oas.models.media.Schema) schemas.get(name).getProperties().get(field))
                .setEnum(List.of(values));
    }

    private static void addStandardError(
            io.swagger.v3.oas.models.responses.ApiResponses responses,
            String status,
            String description) {
        if (!responses.containsKey(status)) {
            responses.addApiResponse(status, new ApiResponse().description(description));
        }
    }

    @Bean
    WebMvcConfigurer frameworkProblemDetailCustomizer() {
        return new WebMvcConfigurer() {
            @Override
            public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
                interceptors.add((problem, error) -> {
                    if (problem.getProperties() != null && problem.getProperties().containsKey("code")) {
                        return;
                    }
                    String code = switch (problem.getStatus()) {
                        case 405 -> "method_not_allowed";
                        case 406 -> "not_acceptable";
                        default -> "invalid_request";
                    };
                    problem.setType(URI.create("https://courtpulse.dev/problems/" + code));
                    problem.setProperty("code", code);
                    String correlationId = MDC.get("correlationId");
                    problem.setProperty("correlationId",
                            correlationId == null ? "unavailable" : correlationId);
                });
            }
        };
    }
}
