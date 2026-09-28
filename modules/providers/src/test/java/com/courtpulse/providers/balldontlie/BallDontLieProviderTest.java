package com.courtpulse.providers.balldontlie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.providers.live.ProviderCircuitBreaker;
import com.courtpulse.providers.live.ProviderClientStats;
import com.courtpulse.providers.live.ProviderException;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.live.ProviderRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BallDontLieProviderTest {
    private static final String KEY = "test-key-never-logged";
    private static final String GAME = """
            {"id":990001,"date":"2026-01-15","status":"2nd Qtr","status_state":"in","period":2,
             "datetime":"2026-01-15T00:10:00.000Z","home_team_score":20,"visitor_team_score":14,
             "home_team":{"id":90001,"full_name":"Harbor City Herons","abbreviation":"HCH"},
             "visitor_team":{"id":90002,"full_name":"Summit Valley Sentinels","abbreviation":"SVS"}}
            """;

    // HTTP/1.1 as in production; a shared client and a pooled server keep a loaded CI host stable.
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverThreads;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile Function<HttpExchange, Response> handler;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = java.util.concurrent.Executors.newFixedThreadPool(4);
        server.setExecutor(serverThreads);
        server.createContext("/", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            paths.add(exchange.getRequestURI().toString());
            Response response = handler.apply(exchange);
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void discoversGamesAcrossCursorPagesWithTheKeyOnlyInTheHeader() {
        AtomicInteger calls = new AtomicInteger();
        handler = exchange -> calls.incrementAndGet() == 1
                ? Response.ok("{\"data\":[" + GAME + "],\"meta\":{\"next_cursor\":7}}")
                : Response.ok("{\"data\":[" + GAME.replace("990001", "990002") + "],\"meta\":{\"next_cursor\":null}}");
        BallDontLieProvider provider = provider(Set.of(), 60);

        List<ProviderGame> games = provider.discover(List.of(LocalDate.parse("2026-01-14"), LocalDate.parse("2026-01-15")));

        assertEquals(List.of("bdl-game-990001", "bdl-game-990002"), games.stream().map(ProviderGame::gameId).toList());
        ProviderGame game = games.getFirst();
        assertEquals(ProviderLifecycle.LIVE, game.lifecycle());
        assertEquals("HCH", game.homeTeam().abbreviation());
        assertEquals("bdl-team-90002", game.awayTeam().teamId());
        assertEquals(List.of(KEY, KEY), authorizations);
        assertTrue(paths.getFirst().startsWith("/v1/games?per_page=100&dates%5B%5D=2026-01-14&dates%5B%5D=2026-01-15"));
        assertTrue(paths.get(1).endsWith("&cursor=7"));
        assertTrue(paths.stream().noneMatch(path -> path.contains(KEY)));
        assertEquals(2, provider.drainStats().requests());
        assertEquals(0, provider.drainStats().requests(), "stats are drained");
    }

    @Test
    void teamFilterLimitsDiscoveredGames() {
        handler = exchange -> Response.ok("{\"data\":[" + GAME + "],\"meta\":{}}");
        assertEquals(0, provider(Set.of("12345"), 60).discover(List.of(LocalDate.parse("2026-01-15"))).size());
        assertEquals(1, provider(Set.of("90002"), 60).discover(List.of(LocalDate.parse("2026-01-15"))).size());
    }

    @Test
    void tooManyRequestsHonorsRetryAfterAndRepeatedFailuresOpenTheCircuit() {
        handler = exchange -> new Response(429, "{}", java.util.Map.of("Retry-After", "2"));
        MutableClock clock = new MutableClock();
        List<Duration> sleeps = new ArrayList<>();
        BallDontLieProvider provider = new BallDontLieProvider(settings(Set.of()), HTTP,
                new ObjectMapper(), new ProviderRateLimiter(600, Duration.ofSeconds(5), clock, sleeps::add),
                new ProviderCircuitBreaker(3, Duration.ofSeconds(30), clock), clock);

        ProviderException first = assertThrows(ProviderException.class,
                () -> provider.game("990001"));
        assertEquals("http_429", first.code());
        assertEquals(Duration.ofSeconds(2), first.retryAfter());
        assertThrows(ProviderException.class, () -> provider.game("990001"));
        assertTrue(sleeps.contains(Duration.ofSeconds(2)), "the next request waited out Retry-After");
        clock.advance(Duration.ofSeconds(3));
        assertThrows(ProviderException.class, () -> provider.game("990001"));
        ProviderException open = assertThrows(ProviderException.class, () -> provider.game("990001"));
        assertEquals("circuit_open", open.code());
        assertEquals(3, paths.size(), "an open circuit sends no request");
        ProviderClientStats stats = provider.drainStats();
        assertEquals(3, stats.rateLimited());
        assertEquals("OPEN", stats.circuitState());

        handler = exchange -> Response.ok("{\"data\":" + GAME + "}");
        clock.advance(Duration.ofSeconds(31));
        assertTrue(provider.game("990001").isPresent(), "half-open probe succeeds");
        assertEquals("CLOSED", provider.drainStats().circuitState());
    }

    @Test
    void penaltiesLongerThanTheWaitBudgetFailFastInsteadOfSleeping() {
        MutableClock clock = new MutableClock();
        ProviderRateLimiter limiter = new ProviderRateLimiter(60, Duration.ofSeconds(5), clock, duration -> { });
        limiter.pauseFor(Duration.ofMinutes(5));
        assertEquals("rate_limited_local", assertThrows(ProviderException.class, limiter::acquire).code());
    }

    @Test
    void authorizationAndServerFailuresHaveFixedCodesWithoutProviderText() {
        handler = exchange -> new Response(401, "{\"error\":\"secret detail " + KEY + "\"}", java.util.Map.of());
        ProviderException unauthorized = assertThrows(ProviderException.class,
                () -> provider(Set.of(), 600).game("990001"));
        assertEquals("http_401", unauthorized.code());
        assertFalse(unauthorized.retryable());
        assertFalse(String.valueOf(unauthorized.getMessage()).contains(KEY));

        handler = exchange -> new Response(503, "", java.util.Map.of());
        assertEquals("http_5xx", assertThrows(ProviderException.class,
                () -> provider(Set.of(), 600).game("990001")).code());

        handler = exchange -> new Response(404, "", java.util.Map.of());
        assertTrue(provider(Set.of(), 600).game("990001").isEmpty());
    }

    @Test
    void oversizedAndMalformedBodiesAreRejected() {
        handler = exchange -> Response.ok("{\"data\":\"" + "x".repeat(4_096) + "\"}");
        assertEquals("response_too_large", assertThrows(ProviderException.class,
                () -> provider(Set.of(), 600).game("990001")).code());
        handler = exchange -> Response.ok("{not json");
        assertEquals("malformed_response", assertThrows(ProviderException.class,
                () -> provider(Set.of(), 600).game("990001")).code());
    }

    @Test
    void settingsRequireHttpsOrLocalSimulatorAndNeverPrintTheKey() {
        assertThrows(IllegalArgumentException.class, () -> new BallDontLieProvider.Settings(
                URI.create("http://api.balldontlie.io"), KEY, Duration.ofSeconds(5), 2_048, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new BallDontLieProvider.Settings(
                URI.create("https://user:pass@api.balldontlie.io"), KEY, Duration.ofSeconds(5), 2_048, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new BallDontLieProvider.Settings(
                URI.create("https://api.balldontlie.io"), " ", Duration.ofSeconds(5), 2_048, Set.of()));
        var valid = new BallDontLieProvider.Settings(
                URI.create("https://api.balldontlie.io"), KEY, Duration.ofSeconds(5), 2_048, Set.of());
        assertFalse(valid.toString().contains(KEY));
    }

    @Test
    void retryAfterAcceptsSecondsAndBoundsAbsurdValues() {
        assertEquals(Duration.ofSeconds(7), BallDontLieProvider.retryAfter("7"));
        assertEquals(Duration.ofHours(1), BallDontLieProvider.retryAfter("999999"));
        assertEquals(Duration.ofSeconds(60), BallDontLieProvider.retryAfter("soon"));
    }

    private BallDontLieProvider provider(Set<String> teams, int requestsPerMinute) {
        Clock clock = Clock.systemUTC();
        return new BallDontLieProvider(settings(teams), HTTP, new ObjectMapper(),
                new ProviderRateLimiter(requestsPerMinute, Duration.ofSeconds(5), clock, duration -> { }),
                new ProviderCircuitBreaker(5, Duration.ofSeconds(30), clock), clock);
    }

    private BallDontLieProvider.Settings settings(Set<String> teams) {
        return new BallDontLieProvider.Settings(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY,
                Duration.ofSeconds(20), 2_048, teams);
    }

    private record Response(int status, String body, java.util.Map<String, String> headers) {
        static Response ok(String body) {
            return new Response(200, body, java.util.Map.of("Content-Type", "application/json"));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-15T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
