package com.courtpulse.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.courtpulse.persistence.JdbcReplayRepository;
import com.courtpulse.providers.nba.NbaPlayByPlayCsv;
import com.courtpulse.providers.nba.NbaReplayGame;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
    "logging.level.root=ERROR",
    "courtpulse.realtime.publication.enabled=false"
})
@AutoConfigureMockMvc
class ReplaysIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String GAME = "0049900101";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc http;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired JdbcReplayRepository replays;

    @BeforeEach
    void seed() throws Exception {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE replay_catalog, replay_players CASCADE").update();
        replays.saveGame("fixture", NbaReplayGame.from(NbaPlayByPlayCsv.read(new StringReader(new String(
                SyntheticFixtureResources.nbaReplayGame(), StandardCharsets.UTF_8))).get(GAME)), Instant.now());
    }

    @Test
    void catalogAndReplayListArePublicButStartingNeedsSignIn() throws Exception {
        http.perform(get("/api/v1/replays/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].nbaGameId").value(GAME))
                .andExpect(jsonPath("$.items[0].homeTeamAbbreviation").value("HCH"))
                .andExpect(jsonPath("$.items[0].homeScore").value(16))
                .andExpect(jsonPath("$.items[0].plays").value(41))
                .andExpect(jsonPath("$.nextAfter").doesNotExist());
        http.perform(get("/api/v1/replays/games").param("team", "BOS")).andExpect(jsonPath("$.items", hasSize(0)));
        http.perform(get("/api/v1/replays/games").param("team", "bad team")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/replays/sessions")).andExpect(status().isOk());
        http.perform(post("/api/v1/me/replays").contentType(MediaType.APPLICATION_JSON).content(start(10)))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/replays/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    void ownersControlTheirReplaysAndOthersCannotSeeThem() throws Exception {
        String session = startAs("fan-a", 10);

        http.perform(get("/api/v1/replays/sessions"))
                .andExpect(jsonPath("$.items[0].gameId").value("nba-replay-0049900101-1"))
                .andExpect(jsonPath("$.items[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.items[0].totalPlays").value(41));
        http.perform(get("/api/v1/me/replays").with(user("fan-b"))).andExpect(jsonPath("$.items", hasSize(0)));
        http.perform(post("/api/v1/me/replays/" + session + "/pause").with(user("fan-b")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("replay_not_found"));

        http.perform(post("/api/v1/me/replays/" + session + "/pause").with(user("fan-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        http.perform(post("/api/v1/me/replays/" + session + "/pause").with(user("fan-a")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("replay_conflict"));
        http.perform(put("/api/v1/me/replays/" + session + "/speed").with(user("fan-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"speed\":60}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.speed").value(60))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        http.perform(put("/api/v1/me/replays/" + session + "/speed").with(user("fan-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"speed\":500}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/v1/me/replays/" + session + "/resume").with(user("fan-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
        http.perform(post("/api/v1/me/replays/" + session + "/finish").with(user("fan-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.playsReleased").value(41));
        http.perform(post("/api/v1/me/replays/" + session + "/finish").with(user("fan-a")))
                .andExpect(status().isConflict());
        http.perform(post("/api/v1/me/replays/not-a-uuid/pause").with(user("fan-a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operatorsMayControlAnyReplay() throws Exception {
        String session = startAs("fan-a", 10);
        http.perform(post("/api/v1/me/replays/" + session + "/pause")
                        .with(jwt().jwt(value -> value.subject("ops-user"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_courtpulse:ops"))))
                .andExpect(status().isOk());
    }

    @Test
    void quotasAndUnknownGamesAreRefused() throws Exception {
        for (int run = 0; run < 3; run++) {
            startAs("fan-a", 30);
        }
        http.perform(post("/api/v1/me/replays").with(user("fan-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(start(30)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("replay_quota_exceeded"));
        http.perform(get("/api/v1/me/replays").with(user("fan-a"))).andExpect(jsonPath("$.items", hasSize(3)));
        startAs("fan-b", 30);
        http.perform(post("/api/v1/me/replays").with(user("fan-b")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nbaGameId\":\"0000000000\",\"speed\":10}"))
                .andExpect(status().isNotFound());
        http.perform(post("/api/v1/me/replays").with(user("fan-b")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nbaGameId\":\"42\",\"speed\":10}"))
                .andExpect(status().isBadRequest());
    }

    private String startAs(String subject, int speed) throws Exception {
        String body = http.perform(post("/api/v1/me/replays").with(user(subject))
                        .contentType(MediaType.APPLICATION_JSON).content(start(speed)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.speed").value(speed))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("sessionId").asText();
    }

    private static String start(int speed) {
        return "{\"nbaGameId\":\"" + GAME + "\",\"speed\":" + speed + "}";
    }

    private static RequestPostProcessor user(String subject) {
        return jwt().jwt(value -> value.subject(subject));
    }
}
