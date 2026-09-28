package com.courtpulse.providersimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.providers.balldontlie.BallDontLieProvider;
import com.courtpulse.providers.live.ProviderCircuitBreaker;
import com.courtpulse.providers.live.ProviderException;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.live.ProviderPlay;
import com.courtpulse.providers.live.ProviderRateLimiter;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The production adapter must understand everything the local simulator serves. */
class ProviderSimulatorContractTest {
    private static final String KEY = "simulator-contract-key";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private ProviderSimulator simulator;

    @BeforeEach
    void start() throws Exception {
        SimulatedGame game = new SimulatedGame(json, json.readTree(SyntheticFixtureResources.ballDontLieOvertimeGame()),
                Clock.systemUTC(), SimulatedGame.Mode.MANUAL, 60, Duration.ZERO, 0, ZoneId.of("America/New_York"));
        simulator = new ProviderSimulator(0, KEY, game, json, Clock.systemUTC(), 0);
        simulator.start();
    }

    @AfterEach
    void stop() {
        simulator.close();
    }

    @Test
    void adapterFollowsAGameFromScheduledThroughOvertimeFinalAndCorrection() throws Exception {
        BallDontLieProvider provider = provider(KEY);
        var dates = List.of(java.time.LocalDate.now(ZoneId.of("America/New_York")));

        ProviderGame scheduled = provider.discover(dates).getFirst();
        assertEquals(ProviderLifecycle.SCHEDULED, scheduled.lifecycle());
        assertEquals("Harbor City Herons", scheduled.homeTeam().name());
        assertEquals(0, provider.plays(scheduled).size());

        control("release?through=60");
        ProviderGame live = provider.discover(dates).getFirst();
        assertEquals(ProviderLifecycle.LIVE, live.lifecycle());
        assertEquals(60, provider.plays(live).size());

        control("release?through=167");
        ProviderGame fin = provider.game(live.providerGameId()).orElseThrow();
        assertEquals(ProviderLifecycle.FINAL, fin.lifecycle());
        List<ProviderPlay> all = provider.plays(fin);
        assertEquals(167, all.size(), "two cursor pages are followed");
        assertTrue(all.stream().noneMatch(ProviderPlay::rejected));
        assertEquals(5, all.getLast().atRevision(1).period());

        String before = all.get(37).contentHash();
        control("correct");
        assertNotEquals(before, provider.plays(fin).get(37).contentHash());
        assertEquals("Ada Lane", provider.player("9000101").orElseThrow().displayName());
    }

    @Test
    void faultsSurfaceAsFixedProviderCodes() throws Exception {
        control("rate-limit?count=1&retryAfter=1");
        ProviderException limited = assertThrows(ProviderException.class,
                () -> provider(KEY).game("990001"));
        assertEquals("http_429", limited.code());
        assertEquals(Duration.ofSeconds(1), limited.retryAfter());

        control("outage?seconds=30");
        assertEquals("http_5xx", assertThrows(ProviderException.class,
                () -> provider(KEY).game("990001")).code());
        assertEquals("http_401", assertThrows(ProviderException.class,
                () -> provider("wrong-key-value").game("990001")).code());
    }

    private BallDontLieProvider provider(String key) {
        Clock clock = Clock.systemUTC();
        return new BallDontLieProvider(
                new BallDontLieProvider.Settings(URI.create("http://127.0.0.1:" + simulator.port()), key,
                        Duration.ofSeconds(5), 4 * 1024 * 1024, Set.of()),
                http, json, new ProviderRateLimiter(6_000, Duration.ofSeconds(5), clock, Thread::sleep),
                new ProviderCircuitBreaker(5, Duration.ofSeconds(30), clock), clock);
    }

    private void control(String action) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + simulator.port() + "/__simulator/" + action))
                .header("Authorization", KEY).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }
}
