package com.courtpulse.providers.nba;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NbaStatsPlayByPlayCsvTest {
    private static final String HEADER = "actionNumber,clock,period,teamId,teamTricode,personId,playerName,playerNameI,"
            + "scoreHome,scoreAway,description,actionType,subType,shotValue,actionId,gameId\n";
    /**
     * A fictional two-period game in the stats layout: actionNumber repeats, rows arrive out of
     * order, an instant-replay row carries a stale score, a free throw's score is the only sign it
     * was made, and a team rebound puts the team ID in the player column.
     */
    private static final String GAME = HEADER
            + "2,PT12M00.00S,1,0,,0,,,0,0,Start of 1st Period (11:50 PM EST),period,start,0,1,49900201\n"
            + "4,PT11M40.00S,1,9001,HCH,101,Lane,A. Lane,2,0,Lane 14' Jump Shot (2 PTS),Made Shot,Jump Shot,2,2,49900201\n"
            + "6,PT11M20.00S,1,9002,SVS,201,Reed,B. Reed,,,MISS Reed 25' 3PT Jump Shot,Missed Shot,Jump Shot,3,4,49900201\n"
            + "6,PT11M18.00S,1,0,,9001,,,,,Herons Rebound,Rebound,,0,5,49900201\n"
            + "5,PT11M30.00S,1,9002,SVS,201,Reed,B. Reed,,,Reed S.FOUL (P1.T1),Foul,,0,3,49900201\n"
            + "7,PT11M18.00S,1,9002,SVS,201,Reed,B. Reed,9,9,Instant Replay1st Period (11:59 PM EST),Instant Replay,,0,6,49900201\n"
            + "8,PT11M18.00S,1,9002,SVS,201,Reed,B. Reed,2,1,Reed Free Throw 1 of 2 (1 PTS),Free Throw,,0,7,49900201\n"
            + "9,PT11M18.00S,1,9002,SVS,201,Reed,B. Reed,,,MISS Reed Free Throw 2 of 2,Free Throw,,0,8,49900201\n"
            + "10,PT00M00.00S,1,0,,0,,,2,1,End of 1st Period (12:10 AM EST),period,end,0,9,49900201\n"
            + "11,PT12M00.00S,2,0,,0,,,2,1,Start of 2nd Period (12:12 AM EST),period,start,0,10,49900201\n"
            + "12,PT05M00.00S,2,9002,SVS,201,Reed,B. Reed,2,4,Reed 26' 3PT Jump Shot (4 PTS),Made Shot,Jump Shot,3,11,49900201\n"
            + "13,PT00M00.00S,2,0,,0,,,2,4,End of 2nd Period (12:30 AM EST),period,end,0,12,49900201\n";

    @Test
    void detectsTheLayoutFromItsHeaderWithoutConsumingIt() throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(GAME));
        assertTrue(NbaStatsPlayByPlayCsv.detect(reader));
        assertEquals(1, NbaStatsPlayByPlayCsv.read(reader).size(), "the header is still there to read");
        assertFalse(NbaStatsPlayByPlayCsv.detect(new BufferedReader(new StringReader(
                "gameId,actionNumber,orderNumber,clock,timeActual\n"))));
    }

    @Test
    void normalizesRowsIntoLiveDataActionsInActionIdOrder() throws IOException {
        List<NbaAction> actions = game().actions(LocalDate.of(2026, 5, 1));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L),
                actions.stream().map(NbaAction::actionNumber).toList(), "actionId order, unique, plus a game end");
        assertEquals("0049900201", actions.getFirst().nbaGameId());
        assertTrue(actions.getFirst().periodStart());
        assertTrue(actions.getLast().gameEnd());
        assertEquals(2, actions.get(1).points());
        assertEquals(0, actions.get(3).points(), "a missed three scores nothing");
        assertEquals(0, actions.get(5).points(), "the instant-replay row's stale 9-9 is ignored");
        assertEquals(1, actions.get(6).points(), "the free throw is made because the score moved");
        assertEquals(0, actions.get(7).points());
        assertEquals(3, actions.get(10).points());
        NbaAction teamRebound = actions.get(4);
        assertEquals(0, teamRebound.personId(), "a team is not a player");
        assertEquals(9001, teamRebound.teamId());
    }

    @Test
    void timesFollowTheGameClockFromEachReportedPeriodStart() throws IOException {
        List<NbaAction> actions = game().actions(LocalDate.of(2026, 5, 1));

        assertEquals(Instant.parse("2026-05-02T03:50:00Z"), Instant.parse(actions.getFirst().timeActual()),
                "11:50 PM Eastern on the game date");
        assertEquals(Instant.parse("2026-05-02T04:02:52Z"), Instant.parse(actions.get(8).timeActual()),
                "12 minutes of game clock plus 6.5 s for each of the eight later actions, past midnight");
        assertEquals(Instant.parse("2026-05-02T04:12:00Z"), Instant.parse(actions.get(9).timeActual()),
                "the second period starts at its reported 12:12 AM");
        for (int index = 1; index < actions.size(); index++) {
            assertFalse(Instant.parse(actions.get(index).timeActual())
                    .isBefore(Instant.parse(actions.get(index - 1).timeActual())), "times never go backwards");
        }
    }

    @Test
    void buildsAReplayableGameWithTheRecordedResult() throws IOException {
        NbaReplayGame game = NbaReplayGame.from(game().actions(LocalDate.of(2026, 5, 1)));

        assertEquals("HCH", game.home().tricode());
        assertEquals("SVS", game.away().tricode());
        assertEquals(2, game.homeScore());
        assertEquals(4, game.awayScore());
        assertEquals(2, game.periods());
        assertEquals(LocalDate.of(2026, 5, 1), game.gameDate(), "a game is dated by its tip-off, not its end");
        assertTrue(game.durationMillis() > 0);
    }

    @Test
    void refusesAGameWithoutADate() throws IOException {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> game().actions(null));
        assertEquals("no game date", failure.getMessage());
    }

    @Test
    void readsGameDatesFromShotDetail() throws IOException {
        Map<String, LocalDate> dates = NbaStatsPlayByPlayCsv.readGameDates(new StringReader(
                "GRID_TYPE,GAME_ID,GAME_DATE,HTM\nShot Chart Detail,49900201,20260501,HCH\n"
                        + "Shot Chart Detail,49900201,20260501,HCH\nShot Chart Detail,49900202,20261399,SVS\n"));

        assertEquals(Map.of("0049900201", LocalDate.of(2026, 5, 1)), dates, "impossible dates are left out");
    }

    @Test
    void parsesWallTimesAndClocks() {
        assertEquals(java.time.LocalTime.of(20, 44), NbaStatsPlayByPlayCsv.wallTime("Start of 1st Period (8:44 PM EST)"));
        assertEquals(java.time.LocalTime.of(0, 5), NbaStatsPlayByPlayCsv.wallTime("End of 4th Period (12:05 AM EDT)"));
        assertEquals(null, NbaStatsPlayByPlayCsv.wallTime("Start of 1st Period"));
        assertEquals(7_700, NbaStatsPlayByPlayCsv.clockMillis("PT00M07.70S"));
        assertThrows(IllegalArgumentException.class, () -> NbaStatsPlayByPlayCsv.clockMillis("7.7"));
    }

    private static NbaStatsPlayByPlayCsv.Game game() throws IOException {
        return NbaStatsPlayByPlayCsv.read(new StringReader(GAME)).get("0049900201");
    }
}
