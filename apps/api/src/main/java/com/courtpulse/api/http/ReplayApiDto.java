package com.courtpulse.api.http;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReplayApiDto {
    private ReplayApiDto() {}

    @Schema(name = "ReplayGame", description = "A completed real game that can be replayed", requiredProperties = {
        "nbaGameId", "gameDate", "homeTeamName", "homeTeamAbbreviation", "awayTeamName",
        "awayTeamAbbreviation", "homeScore", "awayScore", "periods", "plays", "durationSeconds"
    })
    public record ReplayGame(
            String nbaGameId,
            LocalDate gameDate,
            String homeTeamName,
            String homeTeamAbbreviation,
            String awayTeamName,
            String awayTeamAbbreviation,
            int homeScore,
            int awayScore,
            int periods,
            int plays,
            @Schema(description = "Replay length at 1x, with long breaks shortened") long durationSeconds) {}

    @Schema(name = "ReplayGamePage", requiredProperties = {"items"})
    public record ReplayGamePage(
            List<ReplayGame> items,
            @Schema(nullable = true, description = "Pass as `after` to read the next page") String nextAfter) {}

    @Schema(name = "ReplaySession", description = "One replay of a real game, served as a live CourtPulse game",
            requiredProperties = {"sessionId", "nbaGameId", "gameId", "speed", "status", "playsReleased",
                "totalPlays", "homeTeamName", "awayTeamName", "gameDate", "startedAt"})
    public record ReplaySession(
            UUID sessionId,
            String nbaGameId,
            @Schema(description = "The CourtPulse game this replay feeds") String gameId,
            int speed,
            @Schema(allowableValues = {"RUNNING", "PAUSED", "FINISHED"}) String status,
            int playsReleased,
            int totalPlays,
            String homeTeamName,
            String awayTeamName,
            LocalDate gameDate,
            Instant startedAt) {}

    @Schema(name = "ReplaySessionList", requiredProperties = {"items"})
    public record ReplaySessionList(List<ReplaySession> items) {}

    @Schema(name = "StartReplay", requiredProperties = {"nbaGameId", "speed"})
    public record StartReplayRequest(
            @NotNull @Pattern(regexp = "[0-9]{10}") String nbaGameId,
            @NotNull @Min(1) @Max(120) Integer speed) {}

    @Schema(name = "ReplaySpeed", requiredProperties = {"speed"})
    public record SpeedRequest(@NotNull @Min(1) @Max(120) Integer speed) {}
}
