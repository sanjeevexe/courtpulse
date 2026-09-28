package com.courtpulse.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ApiRestartIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine")
                    .withDatabaseName("courtpulse")
                    .withUsername("courtpulse")
                    .withPassword("test-password");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void durableApiBehaviorSurvivesApplicationRestart() throws Exception {
        try (ConfigurableApplicationContext first = start()) {
            ApiTestData.seed(first.getBean(DataSource.class), true);
            assertSnapshot(first, 20, 18, 14);
        }

        try (ConfigurableApplicationContext restarted = start()) {
            assertSnapshot(restarted, 20, 18, 14);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(CourtPulseApiApplication.class)
                .registerShutdownHook(false)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--courtpulse.realtime.publication.enabled=false",
                        "--logging.level.root=ERROR");
    }

    private void assertSnapshot(
            ConfigurableApplicationContext context, long version, int home, int away) throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/games/game_synthetic_001"))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode snapshot = json.readTree(response.body());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(version, snapshot.path("stateVersion").asLong());
        assertEquals(home, snapshot.path("homeScore").asInt());
        assertEquals(away, snapshot.path("awayScore").asInt());
    }
}
