package com.courtpulse.providers.nba;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NbaReplayTest {
    private final NbaReplayPlayMapper mapper = new NbaReplayPlayMapper(new ObjectMapper());

    @Test
    void parsesTheFixtureAndRestoresNbaGameIds() throws IOException {
        Map<String, List<NbaAction>> games = fixture();

        assertEquals(List.of("0049900101"), List.copyOf(games.keySet()));
        List<NbaAction> actions = games.get("0049900101");
        assertEquals(41, actions.size());
        assertEquals("A. Lane", actions.get(1).playerNameI());
        assertEquals(2, actions.get(1).points());
    }

    @Test
    void readsQuotedFieldsWithCommasQuotesAndLineBreaksInProviderOrder() throws IOException {
        String csv = "gameId,actionNumber,orderNumber,clock,timeActual,period,actionType,scoreHome,scoreAway,description\n"
                + "42,7,20,PT11M00.00S,2026-04-20T23:00:02Z,1,foul,0,0,\"Smith \"\"S.FOUL\"\", review,\nupheld\"\n"
                + "42,4,10,PT12M00.00S,2026-04-20T23:00:00Z,1,period,0,0,Period Start\n";

        List<NbaAction> actions = NbaPlayByPlayCsv.read(new StringReader(csv)).get("0000000042");

        assertEquals(List.of(4L, 7L), actions.stream().map(NbaAction::actionNumber).toList());
        assertEquals("Smith \"S.FOUL\", review,\nupheld", actions.get(1).description());
    }

    @Test
    void rejectsMissingColumnsAndMalformedNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> NbaPlayByPlayCsv.read(new StringReader("gameId,clock\n1,PT12M00.00S\n")));
        String header = "gameId,actionNumber,orderNumber,clock,timeActual,period,actionType,scoreHome,scoreAway\n";
        assertThrows(IllegalArgumentException.class,
                () -> NbaPlayByPlayCsv.read(new StringReader(header + "1,x,1,PT12M00.00S,2026-01-01T00:00:00Z,1,period,0,0\n")));
    }

    @Test
    void buildsTeamsFinalScoreAndOffsetsThatSkipLongBreaks() throws IOException {
        NbaReplayGame game = NbaReplayGame.from(fixture().get("0049900101"));

        assertEquals("HCH", game.home().tricode());
        assertEquals(9001, game.home().teamId());
        assertEquals("SVS", game.away().tricode());
        assertEquals(16, game.homeScore());
        assertEquals(12, game.awayScore());
        assertEquals(4, game.periods());
        assertEquals(LocalDate.of(2026, 4, 20), game.gameDate());
        assertEquals(0, game.actions().getFirst().offsetMillis());
        long longestStep = 0;
        for (int index = 1; index < game.actions().size(); index++) {
            longestStep = Math.max(longestStep,
                    game.actions().get(index).offsetMillis() - game.actions().get(index - 1).offsetMillis());
        }
        assertEquals(NbaReplayGame.MAXIMUM_GAP.toMillis(), longestStep, "the 20-minute halftime is compressed");
        assertTrue(game.durationMillis() < 20 * 60_000, "about 17 minutes of real time becomes a short replay");
    }

    @Test
    void refusesUnfinishedOrInconsistentGames() throws IOException {
        List<NbaAction> actions = fixture().get("0049900101");
        assertThrows(IllegalArgumentException.class,
                () -> NbaReplayGame.from(actions.subList(0, actions.size() - 1)));
        List<NbaAction> tampered = new ArrayList<>(actions);
        NbaAction basket = tampered.get(1);
        tampered.set(1, new NbaAction(basket.nbaGameId(), basket.actionNumber(), basket.orderNumber(),
                basket.clock(), basket.timeActual(), basket.period(), basket.actionType(), basket.subType(),
                basket.personId(), basket.playerNameI(), basket.teamId(), basket.teamTricode(),
                3, 0, basket.shotResult(), basket.description()));
        assertThrows(IllegalArgumentException.class, () -> NbaReplayGame.from(tampered));
    }

    @Test
    void everyMappedActionReducesToTheRecordedFinalScore() throws IOException {
        NbaReplayGame game = NbaReplayGame.from(fixture().get("0049900101"));
        ProviderGame providerGame = providerGame(game);
        GameState state = GameState.initial(providerGame.gameId(), providerGame.homeTeam().teamId(),
                providerGame.awayTeam().teamId());
        List<EventType> types = new ArrayList<>();
        for (NbaReplayGame.TimedAction action : game.actions()) {
            ProviderPlay play = mapper.map(action, providerGame);
            assertEquals(null, play.rejectionCode(), "action " + action.action().actionNumber());
            CanonicalEvent event = play.atRevision(1);
            types.add(event.type());
            state = GameReducer.apply(state, event);
        }

        assertEquals(GameStatus.FINAL, state.status());
        assertEquals(16, state.homeScore());
        assertEquals(12, state.awayScore());
        assertEquals(16, state.playerPoints().get("nba-player-101"), "4 baskets and 8 free throws");
        assertEquals(EventType.GAME_STARTED, types.getFirst());
        assertEquals(EventType.GAME_FINAL, types.getLast());
        assertEquals(3, types.stream().filter(EventType.PERIOD_STARTED::equals).count());
        assertTrue(types.contains(EventType.FREE_THROW_MADE));
    }

    @Test
    void mapsIdentitiesDescriptionsAndClocksDeterministically() throws IOException {
        NbaReplayGame game = NbaReplayGame.from(fixture().get("0049900101"));
        ProviderGame providerGame = providerGame(game);
        NbaReplayGame.TimedAction basket = game.actions().get(1);

        ProviderPlay first = mapper.map(basket, providerGame);
        ProviderPlay again = mapper.map(basket, providerGame);

        assertEquals(first.contentHash(), again.contentHash());
        assertEquals("0049900101-1:" + basket.action().actionNumber(), first.providerEventId());
        assertEquals(2, first.sequence());
        CanonicalEvent event = first.atRevision(1);
        assertEquals("nba-replay-0049900101-1", event.gameId());
        assertEquals("nba-team-9001", event.teamId());
        assertEquals(List.of("nba-player-101"), event.participantIds());
        assertEquals(700_000, event.clockMillisRemaining());
        assertEquals("Lane 14' Jump Shot (2 PTS)", event.description());
        assertEquals(754_500, NbaReplayPlayMapper.clockMillis("PT12M34.50S"));
        assertThrows(RuntimeException.class, () -> NbaReplayPlayMapper.clockMillis("12:34"));
    }

    @Test
    void anActionFromAnotherTeamIsRejectedNotGuessed() throws IOException {
        NbaReplayGame game = NbaReplayGame.from(fixture().get("0049900101"));
        NbaAction basket = game.actions().get(1).action();
        NbaAction stranger = new NbaAction(basket.nbaGameId(), basket.actionNumber(), basket.orderNumber(),
                basket.clock(), basket.timeActual(), basket.period(), basket.actionType(), basket.subType(),
                basket.personId(), basket.playerNameI(), 1234, "XYZ", basket.scoreHome(), basket.scoreAway(),
                basket.shotResult(), basket.description());

        ProviderPlay play = mapper.map(new NbaReplayGame.TimedAction(2, 0, stranger), providerGame(game));

        assertEquals("unknown_team", play.rejectionCode());
    }

    private static ProviderGame providerGame(NbaReplayGame game) {
        return new ProviderGame(NbaReplayPlayMapper.SOURCE, game.nbaGameId() + "-1",
                NbaReplayPlayMapper.gameId(game.nbaGameId() + "-1"),
                NbaReplayPlayMapper.team(game.home()), NbaReplayPlayMapper.team(game.away()),
                Instant.parse("2026-09-30T12:00:00Z"), "replay", ProviderLifecycle.LIVE);
    }

    private static Map<String, List<NbaAction>> fixture() throws IOException {
        return NbaPlayByPlayCsv.read(new StringReader(
                new String(SyntheticFixtureResources.nbaReplayGame(), StandardCharsets.UTF_8)));
    }
}
