package com.courtpulse.providersimulator;

import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local test double for the BALLDONTLIE v1 API. It serves only a fictional game and exists so
 * live ingestion, rate limiting, outages, and provider corrections can be exercised without a
 * paid subscription. It must never be deployed or pointed at by a production configuration.
 */
public final class ProviderSimulator implements AutoCloseable {
    private final HttpServer server;
    private final SimulatedGame game;
    private final ObjectMapper json;
    private final byte[] apiKey;
    private final Clock clock;
    private final int rateLimitEvery;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicInteger forced429 = new AtomicInteger();
    private volatile int forcedRetryAfterSeconds = 1;
    private volatile Instant outageUntil = Instant.EPOCH;

    ProviderSimulator(int port, String apiKey, SimulatedGame game, ObjectMapper json, Clock clock,
            int rateLimitEvery) throws IOException {
        if (apiKey == null || apiKey.length() < 8) {
            throw new IllegalArgumentException("SIMULATOR_API_KEY must be at least 8 characters");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.game = game;
        this.json = json;
        this.clock = clock;
        this.rateLimitEvery = rateLimitEvery;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode fixture = json.readTree(SyntheticFixtureResources.ballDontLieOvertimeGame());
        Clock clock = Clock.systemUTC();
        SimulatedGame game = new SimulatedGame(json, fixture, clock,
                SimulatedGame.Mode.valueOf(env("SIMULATOR_MODE", "clock").toUpperCase(java.util.Locale.ROOT)),
                Double.parseDouble(env("SIMULATOR_SPEED", "60")),
                Duration.ofSeconds(Long.parseLong(env("SIMULATOR_START_DELAY_SECONDS", "20"))),
                Long.parseLong(env("SIMULATOR_CORRECT_AFTER_ORDER", "0")),
                ZoneId.of(env("SIMULATOR_LEAGUE_ZONE", "America/New_York")));
        ProviderSimulator simulator = new ProviderSimulator(
                Integer.parseInt(env("SIMULATOR_PORT", "8080")), System.getenv("SIMULATOR_API_KEY"),
                game, json, clock, Integer.parseInt(env("SIMULATOR_RATE_LIMIT_EVERY", "0")));
        simulator.start();
        System.out.printf("Provider simulator serving fictional game %s (%d plays) for league date %s%n",
                game.gameId(), game.total(), game.leagueDate());
        Runtime.getRuntime().addShutdownHook(new Thread(simulator::close, "simulator-shutdown"));
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if ("/healthz".equals(path)) {
                send(exchange, 200, json.createObjectNode().put("status", "ok"));
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, json.createObjectNode().put("error", "Unauthorized"));
                return;
            }
            Map<String, List<String>> query = query(uri.getRawQuery());
            if (path.startsWith("/__simulator/")) {
                control(exchange, path, query);
                return;
            }
            long number = requests.incrementAndGet();
            if (clock.instant().isBefore(outageUntil)) {
                send(exchange, 503, json.createObjectNode().put("error", "Service Unavailable"));
                return;
            }
            if (forced429.getAndUpdate(value -> Math.max(0, value - 1)) > 0
                    || (rateLimitEvery > 0 && number % rateLimitEvery == 0)) {
                rateLimited.incrementAndGet();
                exchange.getResponseHeaders().add("Retry-After", Integer.toString(forcedRetryAfterSeconds));
                send(exchange, 429, json.createObjectNode().put("error", "Too Many Requests"));
                return;
            }
            route(exchange, path, query);
        }
    }

    private void route(HttpExchange exchange, String path, Map<String, List<String>> query) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method)) {
            send(exchange, 405, json.createObjectNode().put("error", "Method Not Allowed"));
            return;
        }
        String gamePath = "/v1/games/";
        if ("/v1/games".equals(path)) {
            List<String> dates = query.getOrDefault("dates[]", List.of());
            List<JsonNode> games = dates.isEmpty() || dates.contains(game.leagueDate().toString())
                    ? List.of(game.game()) : List.of();
            send(exchange, 200, game.page(games, cursor(query), perPage(query)));
        } else if (path.startsWith(gamePath) && path.substring(gamePath.length()).equals(game.gameId())) {
            send(exchange, 200, json.createObjectNode().set("data", game.game()));
        } else if ("/v1/plays".equals(path)) {
            if (!List.of(game.gameId()).equals(query.get("game_id"))) {
                send(exchange, 200, game.page(List.of(), 0, perPage(query)));
                return;
            }
            send(exchange, 200, game.page(game.releasedPlays(), cursor(query), perPage(query)));
        } else if (path.startsWith("/v1/players/")) {
            var player = game.player(path.substring("/v1/players/".length()));
            if (player.isPresent()) {
                send(exchange, 200, json.createObjectNode().set("data", player.get()));
            } else {
                send(exchange, 404, json.createObjectNode().put("error", "Not Found"));
            }
        } else {
            send(exchange, 404, json.createObjectNode().put("error", "Not Found"));
        }
    }

    private void control(HttpExchange exchange, String path, Map<String, List<String>> query) throws IOException {
        boolean post = "POST".equals(exchange.getRequestMethod());
        switch (path) {
            case "/__simulator/state" -> { }
            case "/__simulator/release" -> {
                if (!post) { send(exchange, 405, json.createObjectNode()); return; }
                game.releaseThrough(integer(query, "through", 0, 0, game.total()));
            }
            case "/__simulator/correct" -> {
                if (!post) { send(exchange, 405, json.createObjectNode()); return; }
                game.correct();
            }
            case "/__simulator/outage" -> {
                if (!post) { send(exchange, 405, json.createObjectNode()); return; }
                outageUntil = clock.instant().plusSeconds(integer(query, "seconds", 30, 0, 3_600));
            }
            case "/__simulator/rate-limit" -> {
                if (!post) { send(exchange, 405, json.createObjectNode()); return; }
                forcedRetryAfterSeconds = integer(query, "retryAfter", 1, 1, 3_600);
                forced429.set(integer(query, "count", 1, 0, 1_000));
            }
            default -> {
                send(exchange, 404, json.createObjectNode().put("error", "Not Found"));
                return;
            }
        }
        ObjectNode state = json.createObjectNode();
        state.put("gameId", game.gameId());
        state.put("released", game.released());
        state.put("total", game.total());
        state.put("corrected", game.corrected());
        state.put("status", game.game().path("status").asText());
        state.put("requests", requests.get());
        state.put("rateLimited", rateLimited.get());
        state.put("outageRemainingSeconds",
                Math.max(0, Duration.between(clock.instant(), outageUntil).toSeconds()));
        send(exchange, 200, state);
    }

    private boolean authorized(String header) {
        return header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), apiKey);
    }

    private void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = URLDecoder.decode(split < 0 ? pair : pair.substring(0, split), StandardCharsets.UTF_8);
            String value = split < 0 ? "" : URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static int cursor(Map<String, List<String>> query) {
        return integer(query, "cursor", 0, 0, Integer.MAX_VALUE);
    }

    private static int perPage(Map<String, List<String>> query) {
        return integer(query, "per_page", 25, 1, 100);
    }

    private static int integer(Map<String, List<String>> query, String name, int fallback, int minimum, int maximum) {
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(values.getFirst())));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
