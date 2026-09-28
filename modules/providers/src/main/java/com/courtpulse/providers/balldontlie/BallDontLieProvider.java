package com.courtpulse.providers.balldontlie;

import com.courtpulse.providers.live.LiveGameProvider;
import com.courtpulse.providers.live.ProviderCircuitBreaker;
import com.courtpulse.providers.live.ProviderClientStats;
import com.courtpulse.providers.live.ProviderException;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.live.ProviderPlay;
import com.courtpulse.providers.live.ProviderPlayer;
import com.courtpulse.providers.live.ProviderRateLimiter;
import com.courtpulse.providers.live.ProviderTeam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BALLDONTLIE v1 adapter (documented at https://docs.balldontlie.io, reviewed 2026-09-28).
 * Games and teams are available on the free tier; play-by-play requires the paid GOAT tier.
 * The API key is sent only in the Authorization header and never logged or persisted.
 */
public final class BallDontLieProvider implements LiveGameProvider {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "provider-simulator");
    private static final int MAXIMUM_PAGES = 50;

    private final Settings settings;
    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final BallDontLiePlayMapper mapper;
    private final ProviderRateLimiter limiter;
    private final ProviderCircuitBreaker breaker;
    private final Clock clock;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile String lastErrorCode;
    private volatile Instant lastAttemptAt;
    private volatile Instant lastSuccessAt;

    public BallDontLieProvider(Settings settings, HttpClient http, ObjectMapper objectMapper,
            ProviderRateLimiter limiter, ProviderCircuitBreaker breaker, Clock clock) {
        this.settings = settings;
        this.http = http;
        this.objectMapper = objectMapper;
        this.mapper = new BallDontLiePlayMapper(objectMapper);
        this.limiter = limiter;
        this.breaker = breaker;
        this.clock = clock;
    }

    @Override
    public String source() {
        return BallDontLiePlayMapper.SOURCE;
    }

    @Override
    public List<ProviderGame> discover(List<LocalDate> leagueDates) {
        StringBuilder query = new StringBuilder("/v1/games?per_page=100");
        for (LocalDate date : leagueDates) {
            query.append("&").append(encode("dates[]")).append("=").append(date);
        }
        List<ProviderGame> games = new ArrayList<>();
        for (JsonNode node : paged(query.toString())) {
            ProviderGame game = game(node);
            if (settings.teamFilter().isEmpty()
                    || settings.teamFilter().contains(game.homeTeam().providerTeamId())
                    || settings.teamFilter().contains(game.awayTeam().providerTeamId())) {
                games.add(game);
            }
        }
        return games;
    }

    @Override
    public Optional<ProviderGame> game(String providerGameId) {
        requireNumericId(providerGameId);
        return get("/v1/games/" + providerGameId, true).map(body -> game(body.path("data")));
    }

    @Override
    public List<ProviderPlay> plays(ProviderGame game) {
        requireNumericId(game.providerGameId());
        List<ProviderPlay> plays = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : paged("/v1/plays?game_id=" + game.providerGameId() + "&per_page=100")) {
            ProviderPlay play = mapper.map(node, game);
            if (!seen.add(play.providerEventId())) {
                // Two different bodies for one order: keep the first, surface the second.
                play = new ProviderPlay(play.providerEventId() + ":duplicate:" + play.contentHash().substring(0, 16),
                        play.sequence(), play.rawPayload(), play.contentHash(), null, "duplicate_order");
            }
            plays.add(play);
        }
        plays.sort(Comparator.comparingLong(ProviderPlay::sequence).thenComparing(ProviderPlay::providerEventId));
        return plays;
    }

    @Override
    public Optional<ProviderPlayer> player(String providerPlayerId) {
        requireNumericId(providerPlayerId);
        return get("/v1/players/" + providerPlayerId, true).map(body -> {
            JsonNode data = body.path("data");
            String name = BallDontLiePlayMapper.description(
                    (data.path("first_name").asText("") + " " + data.path("last_name").asText("")).strip());
            if (name == null || name.length() > 120) {
                throw new ProviderException("malformed_response", false);
            }
            return new ProviderPlayer(BallDontLiePlayMapper.playerId(providerPlayerId), providerPlayerId, name);
        });
    }

    @Override
    public Optional<String> providerPlayerId(String playerId) {
        String prefix = BallDontLiePlayMapper.playerId("");
        if (playerId == null || !playerId.startsWith(prefix)) {
            return Optional.empty();
        }
        String id = playerId.substring(prefix.length());
        return id.matches("[1-9][0-9]{0,18}") ? Optional.of(id) : Optional.empty();
    }

    @Override
    public ProviderClientStats drainStats() {
        return new ProviderClientStats(requests.getAndSet(0), rateLimited.getAndSet(0),
                failures.getAndSet(0), breaker.state().name(), lastErrorCode, lastAttemptAt,
                lastSuccessAt, breaker.consecutiveFailures());
    }

    private List<JsonNode> paged(String pathAndQuery) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAXIMUM_PAGES; page++) {
            String path = cursor == null ? pathAndQuery : pathAndQuery + "&cursor=" + encode(cursor);
            JsonNode body = get(path, false).orElseThrow();
            JsonNode data = body.path("data");
            if (!data.isArray()) {
                throw new ProviderException("malformed_response", false);
            }
            data.forEach(items::add);
            JsonNode next = body.path("meta").path("next_cursor");
            if (next.isMissingNode() || next.isNull() || next.asText().isBlank()) {
                return items;
            }
            cursor = next.asText();
        }
        throw new ProviderException("response_too_large", false);
    }

    private Optional<JsonNode> get(String pathAndQuery, boolean notFoundIsEmpty) {
        try {
            breaker.beforeRequest();
        } catch (ProviderException exception) {
            lastErrorCode = exception.code();
            throw exception;
        }
        try {
            limiter.acquire();
        } catch (ProviderException exception) {
            lastErrorCode = exception.code();
            breaker.recordSuccess(); // no request was attempted; release a half-open probe
            throw exception;
        }
        HttpRequest request = HttpRequest.newBuilder(settings.baseUri().resolve(pathAndQuery))
                .timeout(settings.requestTimeout())
                .header("Authorization", settings.apiKey())
                .header("Accept", "application/json")
                .header("User-Agent", "CourtPulse/0.1 (portfolio project)")
                .GET()
                .build();
        requests.incrementAndGet();
        lastAttemptAt = clock.instant();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw failed("timeout", true, null);
        } catch (ConnectException exception) {
            throw failed("connection_failed", true, null);
        } catch (IOException exception) {
            throw failed("connection_failed", true, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderException("interrupted", false);
        }
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status == 200) {
                JsonNode parsed = parse(body);
                breaker.recordSuccess();
                lastSuccessAt = clock.instant();
                lastErrorCode = null;
                return Optional.of(parsed);
            }
            if (status == 404) {
                breaker.recordSuccess();
                if (notFoundIsEmpty) {
                    return Optional.empty();
                }
                throw failed("http_404", false, null);
            }
            if (status == 429) {
                rateLimited.incrementAndGet();
                Duration retryAfter = retryAfter(response.headers().firstValue("Retry-After").orElse(null));
                limiter.pauseFor(retryAfter);
                throw failed("http_429", true, retryAfter);
            }
            if (status == 401) {
                throw failed("http_401", false, null);
            }
            if (status == 403) {
                throw failed("http_403", false, null);
            }
            if (status >= 500) {
                throw failed("http_5xx", true, null);
            }
            throw failed("http_4xx", false, null);
        } catch (IOException exception) {
            throw failed("connection_failed", true, null);
        }
    }

    private JsonNode parse(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(settings.maximumResponseBytes() + 1);
        if (bytes.length > settings.maximumResponseBytes()) {
            throw failed("response_too_large", false, null);
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (JsonProcessingException exception) {
            throw failed("malformed_response", false, null);
        }
    }

    private ProviderException failed(String code, boolean retryable, Duration retryAfter) {
        failures.incrementAndGet();
        lastErrorCode = code;
        if (!"http_404".equals(code)) {
            breaker.recordFailure();
        }
        return new ProviderException(code, retryable, retryAfter);
    }

    static Duration retryAfter(String header) {
        Duration fallback = Duration.ofSeconds(60);
        if (header == null || header.isBlank()) {
            return fallback;
        }
        try {
            long seconds = Long.parseLong(header.strip());
            return Duration.ofSeconds(Math.max(1, Math.min(seconds, 3_600)));
        } catch (NumberFormatException ignored) {
            try {
                Duration until = Duration.between(Instant.now(),
                        ZonedDateTime.parse(header.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return until.isNegative() ? Duration.ofSeconds(1)
                        : until.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : until;
            } catch (DateTimeParseException exception) {
                return fallback;
            }
        }
    }

    ProviderGame game(JsonNode node) {
        String id = numericText(node.path("id"));
        ProviderTeam home = team(node.path("home_team"));
        ProviderTeam away = team(node.path("visitor_team"));
        String status = BallDontLiePlayMapper.description(node.path("status").asText(""));
        if (status == null || status.length() > 40) {
            status = "unknown";
        }
        Instant scheduledAt = null;
        String datetime = node.path("datetime").asText(null);
        if (datetime != null) {
            try {
                scheduledAt = Instant.parse(datetime);
            } catch (DateTimeParseException ignored) {
                scheduledAt = null;
            }
        }
        try {
            return new ProviderGame(source(), id, BallDontLiePlayMapper.gameId(id), home, away,
                    scheduledAt, status, lifecycle(status, node.path("status_state").asText(null),
                            node.path("period").asInt(0)));
        } catch (IllegalArgumentException exception) {
            throw new ProviderException("malformed_response", false);
        }
    }

    static ProviderLifecycle lifecycle(String status, String statusState, int period) {
        if (statusState != null) {
            switch (statusState.toLowerCase(Locale.ROOT)) {
                case "pre" -> { return ProviderLifecycle.SCHEDULED; }
                case "in" -> { return ProviderLifecycle.LIVE; }
                case "post" -> { return ProviderLifecycle.FINAL; }
                default -> { }
            }
        }
        if (status != null && status.toLowerCase(Locale.ROOT).startsWith("final")) {
            return ProviderLifecycle.FINAL;
        }
        return period > 0 ? ProviderLifecycle.LIVE : ProviderLifecycle.SCHEDULED;
    }

    private static ProviderTeam team(JsonNode node) {
        String id = numericText(node.path("id"));
        String name = BallDontLiePlayMapper.description(node.path("full_name").asText(null));
        String abbreviation = node.path("abbreviation").asText("").toUpperCase(Locale.ROOT);
        boolean labelled = name != null && name.length() <= 120 && abbreviation.matches("[A-Z0-9]{2,8}");
        return new ProviderTeam(BallDontLiePlayMapper.teamId(id), id,
                labelled ? name : null, labelled ? abbreviation : null);
    }

    private static String numericText(JsonNode node) {
        if (!node.isIntegralNumber() || node.asLong() < 1) {
            throw new ProviderException("malformed_response", false);
        }
        return Long.toString(node.asLong());
    }

    private static void requireNumericId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Provider IDs are positive integers");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Validated client settings. {@link #toString()} never includes the API key. */
    public record Settings(
            URI baseUri,
            String apiKey,
            Duration requestTimeout,
            int maximumResponseBytes,
            Set<String> teamFilter) {
        public Settings {
            if (baseUri == null || baseUri.getHost() == null || baseUri.getUserInfo() != null
                    || baseUri.getQuery() != null || baseUri.getFragment() != null) {
                throw new IllegalArgumentException("Provider base URL must be an absolute origin");
            }
            boolean local = LOCAL_HOSTS.contains(baseUri.getHost());
            if (!"https".equals(baseUri.getScheme()) && !("http".equals(baseUri.getScheme()) && local)) {
                throw new IllegalArgumentException("Provider base URL must use HTTPS unless it is a local simulator");
            }
            if (apiKey == null || apiKey.isBlank() || apiKey.length() > 200 || !apiKey.matches("[\\x21-\\x7E]+")) {
                throw new IllegalArgumentException("Provider API key is missing or malformed");
            }
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                    || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Provider request timeout must be between 0 and 30 seconds");
            }
            if (maximumResponseBytes < 1_024 || maximumResponseBytes > 16 * 1_024 * 1_024) {
                throw new IllegalArgumentException("Provider response limit must be between 1 KiB and 16 MiB");
            }
            teamFilter = Set.copyOf(teamFilter);
            if (teamFilter.stream().anyMatch(id -> !id.matches("[1-9][0-9]{0,18}"))) {
                throw new IllegalArgumentException("Team filter entries must be provider team IDs");
            }
            String path = baseUri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new IllegalArgumentException("Provider base URL must not contain a path");
            }
        }

        @Override
        public String toString() {
            return "Settings[baseUri=" + baseUri + ", apiKey=<redacted>, requestTimeout=" + requestTimeout
                    + ", maximumResponseBytes=" + maximumResponseBytes + ", teamFilter=" + teamFilter + "]";
        }
    }
}
