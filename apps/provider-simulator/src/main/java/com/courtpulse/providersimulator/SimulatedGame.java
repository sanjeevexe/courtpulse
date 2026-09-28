package com.courtpulse.providersimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One fictional game unfolding over time. Plays are released on a scaled clock (or on demand)
 * and presented exactly as the documented provider would: game status, running score, cursor
 * pages, and an optional correction to an earlier play.
 */
final class SimulatedGame {
    private static final DateTimeFormatter WALLCLOCK = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    enum Mode { CLOCK, MANUAL }

    private final ObjectMapper json;
    private final JsonNode fixture;
    private final List<JsonNode> plays = new ArrayList<>();
    private final List<Duration> offsets = new ArrayList<>();
    private final Clock clock;
    private final Mode mode;
    private final Instant gameStart;
    private final LocalDate leagueDate;
    private final ZoneId leagueZone;
    private final long correctAfterOrder;
    private final List<Instant> manualReleases = new ArrayList<>();
    private boolean corrected;

    SimulatedGame(ObjectMapper json, JsonNode fixture, Clock clock, Mode mode, double speed,
            Duration startDelay, long correctAfterOrder, ZoneId leagueZone) {
        if (speed < 1 || speed > 10_000) {
            throw new IllegalArgumentException("speed must be between 1 and 10000");
        }
        this.json = json;
        this.fixture = fixture;
        this.clock = clock;
        this.mode = mode;
        this.correctAfterOrder = correctAfterOrder;
        this.leagueZone = leagueZone;
        this.gameStart = clock.instant().plus(mode == Mode.CLOCK ? startDelay : Duration.ZERO);
        this.leagueDate = LocalDate.ofInstant(gameStart, leagueZone);
        Instant first = Instant.parse(fixture.path("plays").get(0).path("wallclock").asText());
        for (JsonNode play : fixture.path("plays")) {
            plays.add(play);
            Duration original = Duration.between(first, Instant.parse(play.path("wallclock").asText()));
            offsets.add(Duration.ofNanos((long) (original.toNanos() / speed)));
        }
    }

    synchronized int released() {
        if (mode == Mode.MANUAL) {
            return manualReleases.size();
        }
        Duration elapsed = Duration.between(gameStart, clock.instant());
        if (elapsed.isNegative()) {
            return 0;
        }
        int count = 0;
        while (count < offsets.size() && offsets.get(count).compareTo(elapsed) <= 0) {
            count++;
        }
        return count;
    }

    synchronized int releaseThrough(int order) {
        if (mode != Mode.MANUAL) {
            throw new IllegalStateException("Manual release is only available in manual mode");
        }
        int target = Math.max(0, Math.min(order, plays.size()));
        Instant now = clock.instant();
        while (manualReleases.size() < target) {
            manualReleases.add(now.plusMillis(manualReleases.size() % 1_000));
        }
        return manualReleases.size();
    }

    synchronized void correct() {
        corrected = true;
    }

    synchronized boolean corrected() {
        return corrected || (correctAfterOrder > 0 && released() >= correctAfterOrder);
    }

    int total() {
        return plays.size();
    }

    String gameId() {
        return fixture.path("game").path("id").asText();
    }

    LocalDate leagueDate() {
        return leagueDate;
    }

    synchronized List<JsonNode> releasedPlays() {
        int count = released();
        int correctionOrder = fixture.path("correction").path("order").asInt();
        boolean applyCorrection = corrected();
        List<JsonNode> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            JsonNode source = plays.get(index);
            if (applyCorrection && source.path("order").asInt() == correctionOrder) {
                source = fixture.path("correction").path("play");
            }
            ObjectNode play = source.deepCopy();
            play.put("wallclock", WALLCLOCK.format(releaseTime(index)));
            result.add(play);
        }
        return result;
    }

    synchronized ObjectNode game() {
        ObjectNode game = fixture.path("game").deepCopy();
        List<JsonNode> released = releasedPlays();
        game.put("date", leagueDate.toString());
        game.put("datetime", WALLCLOCK.format(gameStart));
        if (released.isEmpty()) {
            game.put("status", DateTimeFormatter.ofPattern("h:mm a", Locale.US)
                    .format(gameStart.atZone(leagueZone)).toLowerCase(Locale.US) + " ET");
            game.put("status_state", "pre");
            game.put("period", 0);
            game.put("time", "");
            game.put("home_team_score", 0);
            game.put("visitor_team_score", 0);
            return game;
        }
        JsonNode last = released.getLast();
        int period = last.path("period").asInt();
        boolean finished = "End Game".equals(last.path("type").asText());
        String status;
        if (finished) {
            status = "Final";
        } else if (period == 2 && "End Period".equals(last.path("type").asText())) {
            status = "Halftime";
        } else if (period <= 4) {
            status = List.of("1st Qtr", "2nd Qtr", "3rd Qtr", "4th Qtr").get(period - 1);
        } else {
            status = period == 5 ? "OT" : (period - 4) + "OT";
        }
        game.put("status", status);
        game.put("status_state", finished ? "post" : "in");
        game.put("period", period);
        game.put("time", finished ? "Final" : last.path("clock").asText());
        game.put("home_team_score", last.path("home_score").asInt());
        game.put("visitor_team_score", last.path("away_score").asInt());
        return game;
    }

    Optional<ObjectNode> player(String id) {
        for (JsonNode player : fixture.path("players")) {
            if (player.path("id").asText().equals(id)) {
                return Optional.of(player.deepCopy());
            }
        }
        return Optional.empty();
    }

    ObjectNode page(List<JsonNode> items, int cursor, int perPage) {
        ObjectNode body = json.createObjectNode();
        ArrayNode data = body.putArray("data");
        int end = Math.min(items.size(), cursor + perPage);
        for (int index = Math.max(0, cursor); index < end; index++) {
            data.add(items.get(index));
        }
        ObjectNode meta = body.putObject("meta");
        meta.put("per_page", perPage);
        if (end < items.size()) {
            meta.put("next_cursor", end);
        } else {
            meta.putNull("next_cursor");
        }
        return body;
    }

    private Instant releaseTime(int index) {
        return mode == Mode.MANUAL ? manualReleases.get(index) : gameStart.plus(offsets.get(index));
    }
}
