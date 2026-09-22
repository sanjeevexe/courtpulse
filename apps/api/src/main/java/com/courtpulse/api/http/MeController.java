package com.courtpulse.api.http;

import com.courtpulse.api.ownership.OwnershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@SecurityRequirement(name = "oidcBearer")
public final class MeController {
    private final OwnershipService ownership;

    public MeController(OwnershipService ownership) {
        this.ownership = ownership;
    }

    @Operation(summary = "Read the authenticated application user")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Authenticated application user",
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = ApiDto.Me.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @GetMapping
    public ApiDto.Me me(@AuthenticationPrincipal Jwt jwt) {
        ownership.observeUser(jwt.getSubject());
        return new ApiDto.Me(jwt.getSubject());
    }

    @Operation(summary = "List games followed by the authenticated user")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Owned followed games",
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = ApiDto.FollowedGamePage.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @GetMapping("/followed-games")
    public ApiDto.FollowedGamePage followedGames(@AuthenticationPrincipal Jwt jwt) {
        return new ApiDto.FollowedGamePage(ownership.followedGames(jwt.getSubject()).stream()
                .map(value -> new ApiDto.FollowedGame(value.gameId(), value.followedAt()))
                .toList());
    }

    @Operation(summary = "Follow a public game idempotently")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Existing follow retained",
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = ApiDto.FollowedGame.class))),
        @ApiResponse(
                responseCode = "201",
                description = "Follow created",
                headers = @Header(name = "Location", schema = @Schema(type = "string")),
                content = @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = ApiDto.FollowedGame.class))),
        @ApiResponse(responseCode = "400", description = "Invalid game identifier"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Unknown game")
    })
    @PutMapping("/followed-games/{gameId}")
    public ResponseEntity<ApiDto.FollowedGame> follow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 200) String gameId) {
        boolean created = ownership.follow(jwt.getSubject(), gameId);
        ApiDto.FollowedGame result = ownership.followedGames(jwt.getSubject()).stream()
                .filter(value -> value.gameId().equals(gameId))
                .findFirst()
                .map(value -> new ApiDto.FollowedGame(value.gameId(), value.followedAt()))
                .orElseThrow();
        return ResponseEntity.status(created ? 201 : 200)
                .location(URI.create("/api/v1/me/followed-games/" + gameId))
                .body(result);
    }

    @Operation(summary = "Stop following a game; deleting an absent follow is idempotent")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Follow absent after the request"),
        @ApiResponse(responseCode = "400", description = "Invalid game identifier"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Access denied"),
        @ApiResponse(responseCode = "404", description = "Unknown game")
    })
    @DeleteMapping("/followed-games/{gameId}")
    public ResponseEntity<Void> unfollow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 200) String gameId) {
        ownership.unfollow(jwt.getSubject(), gameId);
        return ResponseEntity.noContent().build();
    }
}
