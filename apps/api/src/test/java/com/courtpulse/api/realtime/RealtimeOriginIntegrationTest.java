package com.courtpulse.api.realtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Behind a CDN the API host differs from the site origin, so the site origin is allowed explicitly. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "logging.level.root=ERROR",
            "courtpulse.realtime.publication.enabled=false",
            "courtpulse.realtime.allowed-origins=https://d1234.cloudfront.net"
        })
class RealtimeOriginIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Test
    void configuredSiteOriginMayOpenTheSocketAndOthersAreRefused() throws Exception {
        WebSocket allowed = connect("https://d1234.cloudfront.net");
        assertTrue(!allowed.isOutputClosed(), "the configured CloudFront origin is accepted");
        allowed.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

        CompletionException refused = assertThrows(CompletionException.class,
                () -> connect("https://attacker.example"));
        assertInstanceOf(WebSocketHandshakeException.class, refused.getCause());
    }

    private WebSocket connect(String origin) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", origin)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/v1/games"), new WebSocket.Listener() { })
                .join();
    }
}
