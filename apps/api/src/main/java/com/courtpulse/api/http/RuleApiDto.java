package com.courtpulse.api.http;

import com.courtpulse.domain.alert.RuleType;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RuleApiDto {
    private RuleApiDto() {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PlayerPointsRequest.class, name = "PLAYER_POINTS"),
        @JsonSubTypes.Type(value = CloseGameRequest.class, name = "CLOSE_GAME"),
        @JsonSubTypes.Type(value = ScoringRunRequest.class, name = "SCORING_RUN")
    })
    @Schema(
            name = "CreateAlertRule",
            discriminatorProperty = "type",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "PLAYER_POINTS", schema = PlayerPointsRequest.class),
                @DiscriminatorMapping(value = "CLOSE_GAME", schema = CloseGameRequest.class),
                @DiscriminatorMapping(value = "SCORING_RUN", schema = ScoringRunRequest.class)
            },
            oneOf = {PlayerPointsRequest.class, CloseGameRequest.class, ScoringRunRequest.class})
    public sealed interface CreateRuleRequest
            permits PlayerPointsRequest, CloseGameRequest, ScoringRunRequest {
        RuleType type();
        String gameId();
        Boolean enabled();
    }

    @JsonTypeName("PLAYER_POINTS")
    @Schema(name = "CreatePlayerPointsRule", requiredProperties = {
        "type", "gameId", "playerId", "pointsThreshold"
    })
    public record PlayerPointsRequest(
            @NotNull @Schema(allowableValues = "PLAYER_POINTS") RuleType type,
            @NotBlank @Size(max = 200) String gameId,
            @NotBlank @Size(max = 200) String playerId,
            @NotNull @Min(1) @Max(200) Integer pointsThreshold,
            Boolean enabled) implements CreateRuleRequest {}

    @JsonTypeName("CLOSE_GAME")
    @Schema(name = "CreateCloseGameRule", requiredProperties = {
        "type", "gameId", "maximumMargin", "eligiblePeriod", "maximumClockMillisRemaining"
    })
    public record CloseGameRequest(
            @NotNull @Schema(allowableValues = "CLOSE_GAME") RuleType type,
            @NotBlank @Size(max = 200) String gameId,
            @NotNull @Min(1) @Max(20) Integer maximumMargin,
            @NotNull @Min(1) @Max(10) Integer eligiblePeriod,
            @NotNull @Min(0) @Max(720000) Long maximumClockMillisRemaining,
            Boolean enabled) implements CreateRuleRequest {}

    @JsonTypeName("SCORING_RUN")
    @Schema(name = "CreateScoringRunRule", requiredProperties = {
        "type", "gameId", "teamId", "pointsThreshold"
    })
    public record ScoringRunRequest(
            @NotNull @Schema(allowableValues = "SCORING_RUN") RuleType type,
            @NotBlank @Size(max = 200) String gameId,
            @NotBlank @Size(max = 200) String teamId,
            @NotNull @Min(1) @Max(100) Integer pointsThreshold,
            Boolean enabled) implements CreateRuleRequest {}

    @Schema(name = "UpdateAlertRule", requiredProperties = {"enabled", "version"})
    public record UpdateRuleRequest(@NotNull Boolean enabled, @Min(1) long version) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PlayerPointsRule.class, name = "PLAYER_POINTS"),
        @JsonSubTypes.Type(value = CloseGameRule.class, name = "CLOSE_GAME"),
        @JsonSubTypes.Type(value = ScoringRunRule.class, name = "SCORING_RUN")
    })
    @Schema(
            name = "AlertRule",
            discriminatorProperty = "type",
            oneOf = {PlayerPointsRule.class, CloseGameRule.class, ScoringRunRule.class})
    public sealed interface Rule permits PlayerPointsRule, CloseGameRule, ScoringRunRule {
        UUID id();
        RuleType type();
        String gameId();
        boolean enabled();
        long version();
        Instant createdAt();
        Instant updatedAt();
    }

    @JsonTypeName("PLAYER_POINTS")
    @Schema(requiredProperties = {
        "id", "type", "gameId", "enabled", "playerId", "pointsThreshold",
        "version", "createdAt", "updatedAt"
    })
    public record PlayerPointsRule(
            UUID id, @Schema(allowableValues = "PLAYER_POINTS") RuleType type,
            String gameId, boolean enabled, String playerId,
            int pointsThreshold, long version, Instant createdAt, Instant updatedAt,
            @Schema(nullable = true, description = "Player name when known") String playerName,
            @Schema(nullable = true, description = "\"Away at Home\" when both teams are named") String gameLabel)
            implements Rule {}

    @JsonTypeName("CLOSE_GAME")
    @Schema(requiredProperties = {
        "id", "type", "gameId", "enabled", "maximumMargin", "eligiblePeriod",
        "maximumClockMillisRemaining", "version", "createdAt", "updatedAt"
    })
    public record CloseGameRule(
            UUID id, @Schema(allowableValues = "CLOSE_GAME") RuleType type,
            String gameId, boolean enabled, int maximumMargin,
            int eligiblePeriod, long maximumClockMillisRemaining, long version,
            Instant createdAt, Instant updatedAt,
            @Schema(nullable = true, description = "\"Away at Home\" when both teams are named") String gameLabel)
            implements Rule {}

    @JsonTypeName("SCORING_RUN")
    @Schema(requiredProperties = {
        "id", "type", "gameId", "enabled", "teamId", "pointsThreshold",
        "version", "createdAt", "updatedAt"
    })
    public record ScoringRunRule(
            UUID id, @Schema(allowableValues = "SCORING_RUN") RuleType type,
            String gameId, boolean enabled, String teamId,
            int pointsThreshold, long version, Instant createdAt, Instant updatedAt,
            @Schema(nullable = true, description = "Team name when known") String teamName,
            @Schema(nullable = true, description = "\"Away at Home\" when both teams are named") String gameLabel)
            implements Rule {}

    @Schema(requiredProperties = {"items"})
    public record RulePage(List<Rule> items, @Schema(nullable = true) String nextCursor) {}

    @Schema(requiredProperties = {"items"})
    public record OwnedAlertPage(
            List<OwnedAlert> items, @Schema(nullable = true) String nextCursor) {}

    @Schema(requiredProperties = {
        "id", "ruleId", "ruleType", "gameId", "triggeringEventId", "title",
        "context", "status", "createdAt"
    })
    public record OwnedAlert(
            UUID id,
            String ruleId,
            RuleType ruleType,
            String gameId,
            String triggeringEventId,
            String title,
            Map<String, String> context,
            String status,
            Instant createdAt,
            @Schema(nullable = true, description = "\"Away at Home\" when both teams are named") String gameLabel) {}

    @Schema(requiredProperties = {
        "ownedRules", "enabledOwnedRules", "disabledOwnedRules", "systemRules", "privateAlerts"
    })
    public record RuleOperations(
            long ownedRules,
            long enabledOwnedRules,
            long disabledOwnedRules,
            long systemRules,
            long privateAlerts) {}
}
