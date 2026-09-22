package com.courtpulse.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
    "logging.level.root=ERROR",
    "courtpulse.realtime.publication.enabled=false"
})
@AutoConfigureMockMvc
class SecurityAndOwnershipIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc http;
    @Autowired DataSource dataSource;

    @BeforeEach
    void seed() {
        ApiTestData.seed(dataSource, true);
    }

    @Test
    void publicReadsRemainAnonymousWhileOwnedResourcesRequireAuthentication() throws Exception {
        http.perform(get("/api/v1/auth/config")).andExpect(status().isOk());
        http.perform(get("/api/v1/games")).andExpect(status().isOk());
        http.perform(get("/api/v1/games/game_synthetic_001")).andExpect(status().isOk());
        http.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication_required"));
        http.perform(put("/api/v1/me/followed-games/game_synthetic_001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unclassifiedApiAndActuatorRoutesFailClosed() throws Exception {
        http.perform(get("/api/v1/unclassified")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/unclassified")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isForbidden());
        http.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedSubjectOwnsIdempotentFollowState() throws Exception {
        http.perform(get("/api/v1/me").with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("user-a"));
        http.perform(put("/api/v1/me/followed-games/game_synthetic_001")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isCreated());
        http.perform(put("/api/v1/me/followed-games/game_synthetic_001")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isOk());
        http.perform(get("/api/v1/me/followed-games")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].gameId").value("game_synthetic_001"));
    }

    @Test
    void ordinaryReadsDoNotContinuouslyRewriteLastSeenTime() throws Exception {
        http.perform(get("/api/v1/me").with(jwt().jwt(value -> value.subject("bounded-reader"))))
                .andExpect(status().isOk());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        OffsetDateTime firstSeen = jdbc.sql(
                        "SELECT last_seen_at FROM application_users WHERE subject = 'bounded-reader'")
                .query(OffsetDateTime.class)
                .single();

        http.perform(get("/api/v1/me").with(jwt().jwt(value -> value.subject("bounded-reader"))))
                .andExpect(status().isOk());
        OffsetDateTime secondSeen = jdbc.sql(
                        "SELECT last_seen_at FROM application_users WHERE subject = 'bounded-reader'")
                .query(OffsetDateTime.class)
                .single();

        org.junit.jupiter.api.Assertions.assertEquals(firstSeen, secondSeen);
    }

    @Test
    void oneSubjectCannotObserveOrMutateAnotherSubjectsFollow() throws Exception {
        http.perform(put("/api/v1/me/followed-games/game_synthetic_001")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isCreated());

        http.perform(get("/api/v1/me/followed-games")
                        .with(jwt().jwt(value -> value.subject("user-b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        http.perform(delete("/api/v1/me/followed-games/game_synthetic_001?subject=user-a")
                        .with(jwt().jwt(value -> value.subject("user-b"))))
                .andExpect(status().isNoContent());
        http.perform(get("/api/v1/me/followed-games")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void deleteIsIdempotentAndUnknownGamesAreRejected() throws Exception {
        http.perform(delete("/api/v1/me/followed-games/game_synthetic_001")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isNoContent());
        http.perform(delete("/api/v1/me/followed-games/game_synthetic_001")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isNoContent());
        http.perform(put("/api/v1/me/followed-games/missing")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("game_not_found"));
    }

    @Test
    void operationsRequireDedicatedAuthority() throws Exception {
        http.perform(get("/api/v1/operations/processing"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/operations/processing")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_authority"));
        http.perform(get("/api/v1/operations/processing")
                        .with(jwt().jwt(value -> value.subject("operator"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_courtpulse:ops"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedEvents").value(20));
        http.perform(get("/api/v1/operations/rules")
                        .with(jwt().jwt(value -> value.subject("user-a"))))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/operations/rules")
                        .with(jwt().jwt(value -> value.subject("operator"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_courtpulse:ops"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemRules").value(1))
                .andExpect(jsonPath("$.ownedRules").value(0));
    }

    @Test
    void authenticationFailuresAreSanitized() throws Exception {
        String token = "secret.header.payload";
        http.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(token))))
                .andExpect(content().string(not(containsString("BadJwtException"))))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
