package com.courtpulse.persistence;

import com.courtpulse.domain.alert.RuleType;
import java.util.Map;

/** Words for an alert, rendered from its type and verified context with the names known today. */
public final class AlertWording {
    private AlertWording() {}

    public static String title(RuleType type, Map<String, String> context, String storedTitle,
            JdbcDisplayNames.Names names) {
        try {
            return switch (type) {
                case PLAYER_POINTS -> "%s reached %s points".formatted(
                        names.player(context.get("playerId")), required(context, "threshold"));
                case SCORING_RUN -> "%s went on a %s-0 run".formatted(names.team(context.get("teamId")),
                        context.getOrDefault("verifiedRunPoints", required(context, "threshold")));
                case CLOSE_GAME -> closeGame(context);
            };
        } catch (IllegalArgumentException exception) {
            return storedTitle; // an older alert without this context keeps its original text
        }
    }

    private static String closeGame(Map<String, String> context) {
        int margin = Integer.parseInt(required(context, "verifiedMargin"));
        String when = clock(Long.parseLong(required(context, "clockMillisRemaining"))) + " left in "
                + period(Integer.parseInt(required(context, "period")));
        return margin == 0 ? "Tied with " + when : "%d-point game with %s".formatted(margin, when);
    }

    static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    static String period(int period) {
        return period <= 4 ? "Q" + period : period == 5 ? "OT" : (period - 4) + "OT";
    }

    private static String required(Map<String, String> context, String key) {
        String value = context.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }
}
