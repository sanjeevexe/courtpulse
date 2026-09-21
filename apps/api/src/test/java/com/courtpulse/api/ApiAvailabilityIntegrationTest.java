package com.courtpulse.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "courtpulse.realtime.publication.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiAvailabilityIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.6-alpine")
                    .withDatabaseName("courtpulse")
                    .withUsername("courtpulse")
                    .withPassword("test-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void livenessStaysUpWhenDatabaseReadinessFails() throws Exception {
        assertHealth("/actuator/health/liveness", 200, "UP");
        assertHealth("/actuator/health/readiness", 200, "UP");

        POSTGRES.stop();

        HttpResponse<String> readiness = awaitHealth("/actuator/health/readiness", 503, "DOWN");
        assertEquals(503, readiness.statusCode());
        assertHealth("/actuator/health/liveness", 200, "UP");
    }

    private HttpResponse<String> awaitHealth(String path, int status, String state) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            response = get(path);
            if (response.statusCode() == status
                    && state.equals(json.readTree(response.body()).path("status").asText())) {
                return response;
            }
            Thread.sleep(250);
        }
        return response;
    }

    private void assertHealth(String path, int status, String state) throws Exception {
        HttpResponse<String> response = get(path);
        JsonNode body = json.readTree(response.body());
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(state, body.path("status").asText(), response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
