package com.courtpulse.api.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.query.DataStatus;
import com.courtpulse.query.GameSnapshotReadModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GamesControllerTest {
    @Test
    void snapshotMapsReadModelAndHonorsConditionalGet() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        GameSnapshotReadModel snapshot = new GameSnapshotReadModel(
                "game-1", "synthetic", "home", "away", "FINAL", 4, 0,
                18, 14, Map.of("player_ace", 13), 20, 20, "checksum",
                List.of(), Instant.parse("2026-09-20T16:00:00Z"), DataStatus.FINAL);
        when(queries.game("game-1")).thenReturn(snapshot);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new GamesController(queries)).build();

        String etag = SnapshotEtag.of(snapshot);
        mvc.perform(get("/api/v1/games/game-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etag))
                .andExpect(jsonPath("$.homeScore").value(18))
                .andExpect(jsonPath("$.playerPoints.player_ace").value(13));

        mvc.perform(get("/api/v1/games/game-1").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(jsonPath("$").doesNotExist());
    }
}
