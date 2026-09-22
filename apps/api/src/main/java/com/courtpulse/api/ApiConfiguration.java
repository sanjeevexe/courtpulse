package com.courtpulse.api;

import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.api.realtime.RealtimeHub;
import com.courtpulse.api.realtime.RealtimeOutboxPublisher;
import com.courtpulse.api.realtime.RealtimeProtocol;
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
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
import com.courtpulse.api.ownership.OwnershipService;
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
