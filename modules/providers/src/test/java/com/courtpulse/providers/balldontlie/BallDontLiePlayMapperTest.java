package com.courtpulse.providers.balldontlie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.game.GameReducer;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.game.GameStatus;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.live.ProviderPlay;
import com.courtpulse.providers.live.ProviderTeam;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BallDontLiePlayMapperTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BallDontLiePlayMapper mapper = new BallDontLiePlayMapper(JSON);

    @Test
    void entireSyntheticOvertimeGameReplaysToTheDocumentedFinalState() throws Exception {
        JsonNode fixture = JSON.readTree(SyntheticFixtureResources.ballDontLieOvertimeGame());
        ProviderGame game = game(fixture.path("game"));
        GameState state = GameState.initial(game.gameId(), game.homeTeam().teamId(), game.awayTeam().teamId());
        List<CanonicalEvent> events = new ArrayList<>();
        for (JsonNode play : fixture.path("plays")) {
            ProviderPlay mapped = mapper.map(play, game);
            assertFalse(mapped.rejected(), () -> "rejected: " + mapped.rejectionCode());
            CanonicalEvent event = mapped.atRevision(1);
            events.add(event);
            state = GameReducer.apply(state, event);
        }
        JsonNode expected = fixture.path("expected");
        assertEquals(GameStatus.FINAL, state.status());
        assertEquals(expected.path("finalScore").path("home").asInt(), state.homeScore());
        assertEquals(expected.path("finalScore").path("away").asInt(), state.awayScore());
        assertEquals(5, state.period(), "the game must finish in overtime");
        assertEquals(EventType.GAME_STARTED, events.getFirst().type());
        assertEquals(EventType.GAME_FINAL, events.getLast().type());
        expected.path("playerPoints").properties().forEach(entry ->
                assertEquals(entry.getValue().asInt(), stateFor(entry.getKey(), events)));
        assertTrue(events.stream().anyMatch(event -> event.period() == 5
                && event.clockMillisRemaining() <= 300_000));
        assertEquals("990001:1", events.getFirst().providerEventId());
        assertEquals("bdl-990001-1-r1", events.getFirst().eventId());
    }

    @Test
    void correctedPlayKeepsIdentityButChangesEvidenceAndScorer() throws Exception {
        JsonNode fixture = JSON.readTree(SyntheticFixtureResources.ballDontLieOvertimeGame());
        ProviderGame game = game(fixture.path("game"));
        int order = fixture.path("correction").path("order").asInt();
        ProviderPlay original = mapper.map(fixture.path("plays").get(order - 1), game);
        ProviderPlay corrected = mapper.map(fixture.path("correction").path("play"), game);
        assertEquals(original.providerEventId(), corrected.providerEventId());
        assertNotEquals(original.contentHash(), corrected.contentHash());
        CanonicalEvent revision2 = corrected.atRevision(2);
        assertEquals("bdl-990001-" + order + "-r2", revision2.eventId());
        assertEquals("bdl-player-" + fixture.path("expected").path("correctedTo").asText(),
                revision2.participantIds().getFirst());
    }

    @Test
    void canonicalRawEvidenceIsIndependentOfProviderKeyOrder() throws Exception {
        JsonNode a = JSON.readTree("{\"order\":2,\"game_id\":1,\"team\":{\"name\":\"x\",\"id\":1}}");
        JsonNode b = JSON.readTree("{\"team\":{\"id\":1,\"name\":\"x\"},\"game_id\":1,\"order\":2}");
        assertEquals(mapper.canonicalJson(a), mapper.canonicalJson(b));
    }

    @Test
    void malformedPlaysBecomeFixedRejectionCodesInsteadOfEvents() throws Exception {
        ProviderGame game = game(JSON.readTree(SyntheticFixtureResources.ballDontLieOvertimeGame()).path("game"));
        assertEquals("missing_order", mapper.map(play(p -> p.remove("order")), game).rejectionCode());
        assertEquals("invalid_clock", mapper.map(play(p -> p.put("clock", "13:00")), game).rejectionCode());
        assertEquals("invalid_clock", mapper.map(play(p -> p.put("period", 5).put("clock", "7:00")), game)
                .rejectionCode());
        assertEquals("invalid_period", mapper.map(play(p -> p.put("period", 11)), game).rejectionCode());
        assertEquals("unsupported_score_value", mapper.map(play(p -> p.put("scoring_play", true)
                .put("score_value", 4)), game).rejectionCode());
        assertEquals("unknown_team", mapper.map(play(p -> ((ObjectNode) p.path("team")).put("id", 5)), game)
                .rejectionCode());
        assertEquals("invalid_participants", mapper.map(play(p -> p.putArray("participants").add("x")), game)
                .rejectionCode());
        assertEquals("start_with_score", mapper.map(play(p -> p.put("order", 1).put("home_score", 2)), game)
                .rejectionCode());
        assertThrows(IllegalStateException.class, () -> mapper.map(play(p -> p.remove("order")), game).atRevision(1));
    }

    @Test
    void clocksAndDescriptionsAreParsedAndBoundedSafely() {
        assertEquals(720_000, BallDontLiePlayMapper.clockMillis("12:00"));
        assertEquals(45_300, BallDontLiePlayMapper.clockMillis("45.3"));
        assertEquals(65_250, BallDontLiePlayMapper.clockMillis("1:05.25"));
        assertThrows(RuntimeException.class, () -> BallDontLiePlayMapper.clockMillis("1:75"));
        assertNull(BallDontLiePlayMapper.description("  \n\t "));
        assertEquals("Ada makes layup", BallDontLiePlayMapper.description(" Ada\u0000 makes\n layup "));
        String bounded = BallDontLiePlayMapper.description("x".repeat(400));
        assertEquals(CanonicalEvent.MAXIMUM_DESCRIPTION_LENGTH, bounded.length());
        assertTrue(bounded.endsWith("…"));
    }

    @Test
    void lifecyclePrefersProviderStateThenFallsBackToStatusAndPeriod() {
        assertEquals(ProviderLifecycle.LIVE, BallDontLieProvider.lifecycle("2nd Qtr", "in", 2));
        assertEquals(ProviderLifecycle.FINAL, BallDontLieProvider.lifecycle("Final", null, 4));
        assertEquals(ProviderLifecycle.FINAL, BallDontLieProvider.lifecycle("Final/OT", null, 5));
        assertEquals(ProviderLifecycle.LIVE, BallDontLieProvider.lifecycle("Halftime", null, 2));
        assertEquals(ProviderLifecycle.SCHEDULED, BallDontLieProvider.lifecycle("7:30 pm ET", null, 0));
    }

    private static int stateFor(String providerPlayerId, List<CanonicalEvent> events) {
        return events.stream()
                .filter(event -> event.points() > 0
                        && event.participantIds().getFirst().equals("bdl-player-" + providerPlayerId))
                .mapToInt(CanonicalEvent::points).sum();
    }

    private static JsonNode play(java.util.function.Consumer<ObjectNode> change) throws Exception {
        ObjectNode play = (ObjectNode) JSON.readTree(SyntheticFixtureResources.ballDontLieOvertimeGame())
                .path("plays").get(4).deepCopy();
        change.accept(play);
        return play;
    }

    static ProviderGame game(JsonNode game) {
        String home = game.path("home_team").path("id").asText();
        String away = game.path("visitor_team").path("id").asText();
        return new ProviderGame("balldontlie", game.path("id").asText(),
                "bdl-game-" + game.path("id").asText(),
                new ProviderTeam("bdl-team-" + home, home, "Home", "HOM"),
                new ProviderTeam("bdl-team-" + away, away, "Away", "AWY"),
                Instant.parse(game.path("datetime").asText()), "Final", ProviderLifecycle.FINAL);
    }
}
