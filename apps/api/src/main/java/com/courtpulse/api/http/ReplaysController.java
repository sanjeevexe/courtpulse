package com.courtpulse.api.http;

import com.courtpulse.api.replays.ReplayService;
import com.courtpulse.api.security.CourtPulseAuthenticationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Replays of completed real games: a public catalog and replay list, controls for signed-in users. */
@RestController
@Validated
public class ReplaysController {
    private final ReplayService replays;
    private final String operationsAuthority;

    public ReplaysController(ReplayService replays, CourtPulseAuthenticationProperties properties) {
        this.replays = replays;
        this.operationsAuthority = properties.getOperationsAuthority();
    }

    @Operation(summary = "List completed real games available for replay, newest first")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Replay catalog page"),
        @ApiResponse(responseCode = "400", description = "Invalid filter or page request")
    })
    @GetMapping(path = "/api/v1/replays/games", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayApiDto.ReplayGamePage catalog(
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2,5}") String team,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{10}") String after,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return replays.catalog(team, after, limit);
    }

    @Operation(summary = "List running, paused, and recently finished replays")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Replays"))
    @GetMapping(path = "/api/v1/replays/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayApiDto.ReplaySessionList sessions() {
        return replays.sessions();
    }

    @Operation(summary = "List the replays the authenticated user started")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Owned replays"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @SecurityRequirement(name = "oidcBearer")
    @GetMapping(path = "/api/v1/me/replays", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayApiDto.ReplaySessionList mine(@AuthenticationPrincipal Jwt jwt) {
        return replays.mine(jwt.getSubject());
    }

    @Operation(summary = "Start replaying a real game as a live CourtPulse game")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Replay started"),
        @ApiResponse(responseCode = "400", description = "Malformed request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Game not in the replay catalog"),
        @ApiResponse(responseCode = "429", description = "Replay quota exceeded")
    })
    @SecurityRequirement(name = "oidcBearer")
    @PostMapping(path = "/api/v1/me/replays", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReplayApiDto.ReplaySession> start(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReplayApiDto.StartReplayRequest request) {
        ReplayApiDto.ReplaySession session = replays.start(jwt.getSubject(), request.nbaGameId(), request.speed());
        return ResponseEntity.created(URI.create("/api/v1/me/replays/" + session.sessionId())).body(session);
    }

    @Operation(summary = "Pause an owned replay")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Replay paused"),
        @ApiResponse(responseCode = "400", description = "Malformed replay ID"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Replay not found"),
        @ApiResponse(responseCode = "409", description = "Replay is not running")
    })
    @SecurityRequirement(name = "oidcBearer")
    @PostMapping(path = "/api/v1/me/replays/{sessionId}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayApiDto.ReplaySession pause(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return replays.pause(authentication.getName(), operator(authentication), sessionId);
    }

    @Operation(summary = "Resume an owned paused replay")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Replay resumed"),
        @ApiResponse(responseCode = "400", description = "Malformed replay ID"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Replay not found"),
        @ApiResponse(responseCode = "409", description = "Replay is not paused")
    })
    @SecurityRequirement(name = "oidcBearer")
    @PostMapping(path = "/api/v1/me/replays/{sessionId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayApiDto.ReplaySession resume(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return replays.resume(authentication.getName(), operator(authentication), sessionId);
    }

    @Operation(summary = "Skip an owned replay to the final buzzer")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Replay finished"),
        @ApiResponse(responseCode = "400", description = "Malformed replay ID"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Replay not found"),
        @ApiResponse(responseCode = "409", description = "Replay already finished")
    })
    @SecurityRequirement(name = "oidcBearer")
    @PostMapping(path = "/api/v1/me/replays/{sessionId}/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayApiDto.ReplaySession finish(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return replays.finish(authentication.getName(), operator(authentication), sessionId);
    }

    @Operation(summary = "Change an owned replay's speed")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Speed changed"),
        @ApiResponse(responseCode = "400", description = "Malformed request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Replay not found"),
        @ApiResponse(responseCode = "409", description = "Replay already finished")
    })
    @SecurityRequirement(name = "oidcBearer")
    @PutMapping(path = "/api/v1/me/replays/{sessionId}/speed", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayApiDto.ReplaySession speed(JwtAuthenticationToken authentication, @PathVariable UUID sessionId,
            @Valid @RequestBody ReplayApiDto.SpeedRequest request) {
        return replays.speed(authentication.getName(), operator(authentication), sessionId, request.speed());
    }

    private boolean operator(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> operationsAuthority.equals(authority.getAuthority()));
    }
}
