package com.courtpulse.api.realtime;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class RealtimeWebSocketConfiguration implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    private final List<String> allowedOrigins;

    public RealtimeWebSocketConfiguration(
            RealtimeWebSocketHandler handler,
            @Value("${courtpulse.realtime.allowed-origins:}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = parseOrigins(allowedOrigins);
    }

    /**
     * Without configuration only same-origin handshakes are accepted, which is correct behind the
     * local Nginx boundary. Behind a CDN the API sees the load balancer host, so the public site
     * origin must be listed explicitly. Wildcards are never accepted.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler, "/ws/v1/games");
        if (!allowedOrigins.isEmpty()) {
            registration.setAllowedOrigins(allowedOrigins.toArray(String[]::new));
        }
    }

    static List<String> parseOrigins(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> origins = Arrays.stream(value.split(",")).map(String::strip)
                .filter(origin -> !origin.isEmpty()).toList();
        for (String origin : origins) {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("courtpulse.realtime.allowed-origins contains an invalid origin");
            }
            String host = uri.getHost();
            boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
            boolean secure = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback);
            boolean bareOrigin = host != null && !host.contains("*") && uri.getUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
            if (!secure || !bareOrigin) {
                throw new IllegalStateException(
                        "courtpulse.realtime.allowed-origins entries must be HTTPS (or loopback HTTP) origins without paths or wildcards");
            }
        }
        return origins;
    }
}
