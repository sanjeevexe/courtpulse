package com.courtpulse.providers.nba;

/**
 * One NBA.com live-data play-by-play action, reduced to the fields replay uses. Values are kept as
 * the provider reported them; {@link NbaReplayPlayMapper} validates them when mapping.
 */
public record NbaAction(
        String nbaGameId,
        long actionNumber,
        long orderNumber,
        String clock,
        String timeActual,
        int period,
        String actionType,
        String subType,
        long personId,
        String playerNameI,
        long teamId,
        String teamTricode,
        int scoreHome,
        int scoreAway,
        String shotResult,
        String description) {

    /** Points this action adds: 2 or 3 for a made field goal, 1 for a made free throw, else 0. */
    public int points() {
        if (!"Made".equalsIgnoreCase(shotResult)) {
            return 0;
        }
        return switch (actionType) {
            case "2pt" -> 2;
            case "3pt" -> 3;
            case "freethrow" -> 1;
            default -> 0;
        };
    }

    public boolean periodStart() {
        return "period".equals(actionType) && "start".equals(subType);
    }

    public boolean gameEnd() {
        return "game".equals(actionType) && "end".equals(subType);
    }
}
