package com.courtpulse.api.http;

import com.courtpulse.query.DataStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiDto {
    private ApiDto() {}

    @Schema(requiredProperties = {"items"})
    public record GamePage(List<GameSummary> items, @Schema(nullable = true) String nextCursor) {}

    @Schema(requiredProperties = {"items"})
    public record EventPage(List<Event> items, @Schema(nullable = true) String nextCursor) {}

    @Schema(requiredProperties = {"items"})
    public record AlertPage(List<Alert> items, @Schema(nullable = true) String nextCursor) {}

    @Schema(requiredProperties = {"subject"})
    public record Me(@Size(min = 1, max = 255) String subject) {}

    @Schema(requiredProperties = {"items"})
    public record FollowedGamePage(List<FollowedGame> items) {}

    @Schema(requiredProperties = {"gameId", "followedAt"})
    public record FollowedGame(@Size(min = 1, max = 200) String gameId, Instant followedAt) {}

    @Schema(requiredProperties = {"enabled", "issuer", "clientId", "scope"})
    public record AuthenticationConfiguration(
            boolean enabled,
            String issuer,
            String clientId,
            String scope) {}

    @Schema(requiredProperties = {
        "gameId", "source", "homeTeamId", "awayTeamId", "status", "stateVersion",
        "homeScore", "awayScore", "period", "clockMillisRemaining", "lastAppliedSequence",
        "updatedAt", "dataStatus"
    })
    public record GameSummary(
            String gameId,
            String source,
            String homeTeamId,
            String awayTeamId,
            @Schema(allowableValues = {"SCHEDULED", "LIVE", "FINAL"}) String status,
            long stateVersion,
            int homeScore,
            int awayScore,
            int period,
            long clockMillisRemaining,
            long lastAppliedSequence,
            Instant updatedAt,
            @Schema(nullable = true) String stateChecksum,
            DataStatus dataStatus) {}

    @Schema(requiredProperties = {
        "gameId", "source", "homeTeamId", "awayTeamId", "status", "period",
        "clockMillisRemaining", "homeScore", "awayScore", "playerPoints", "stateVersion",
        "lastAppliedSequence", "recentEvents", "updatedAt", "dataStatus"
    })
    public record GameSnapshot(
            String gameId,
            String source,
            String homeTeamId,
            String awayTeamId,
            @Schema(allowableValues = {"SCHEDULED", "LIVE", "FINAL"}) String status,
            int period,
            long clockMillisRemaining,
            int homeScore,
            int awayScore,
            Map<String, Integer> playerPoints,
            long stateVersion,
            long lastAppliedSequence,
            @Schema(nullable = true) String stateChecksum,
            List<RecentEvent> recentEvents,
            Instant updatedAt,
            DataStatus dataStatus) {}

    @Schema(requiredProperties = {
        "eventId", "sequence", "revision", "eventType", "occurredAt", "scoreAfter"
    })
    public record RecentEvent(
            String eventId,
            long sequence,
            int revision,
            String eventType,
            Instant occurredAt,
            Score scoreAfter) {}

    @Schema(requiredProperties = {
        "eventId", "schemaVersion", "gameId", "source", "providerEventId", "sequence",
        "revision", "eventType", "period", "clockMillisRemaining", "occurredAt",
        "participantIds", "scoreAfter", "points"
    })
    public record Event(
            String eventId,
            int schemaVersion,
            String gameId,
            String source,
            String providerEventId,
            long sequence,
            int revision,
            String eventType,
            int period,
            long clockMillisRemaining,
            Instant occurredAt,
            @Schema(nullable = true) String teamId,
            List<String> participantIds,
            Score scoreAfter,
            int points) {}

    @Schema(requiredProperties = {"home", "away"})
    public record Score(int home, int away) {}

    @Schema(requiredProperties = {
        "ruleId", "triggerKey", "gameId", "triggeringEventId", "title", "context",
        "status", "createdAt"
    })
    public record Alert(
            String ruleId,
            String triggerKey,
            String gameId,
            String triggeringEventId,
            String title,
            Map<String, String> context,
            String status,
            Instant createdAt) {}

    @Schema(requiredProperties = {
        "pending", "publishing", "retryScheduled", "sent", "failed", "deferredNotifications",
        "blockedGames", "processedEvents", "alerts", "apiTimestamp"
    })
    public record Processing(
            long pending,
            long publishing,
            long retryScheduled,
            long sent,
            long failed,
            long deferredNotifications,
            @Schema(nullable = true) Long oldestEligiblePendingAgeSeconds,
            long blockedGames,
            long processedEvents,
            long alerts,
            Instant apiTimestamp) {}

    @Schema(name = "Problem", requiredProperties = {
        "type", "title", "status", "detail", "instance", "correlationId", "code"
    })
    public record ProblemExample(
            @Schema(format = "uri") String type,
            String title,
            int status,
            String detail,
            @Schema(format = "uri-reference") String instance,
            String correlationId,
            String code) {}
}
