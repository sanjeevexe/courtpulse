package com.courtpulse.persistence;

import com.courtpulse.domain.alert.RuleType;
import java.util.Map;
import java.util.UUID;

/**
 * A claimed email delivery. {@code title} and {@code gameLabel} are the words a person reads,
 * rendered with display names; the rule type and verified context they came from travel along.
 */
public record ClaimedEmailDelivery(
        UUID id,
        String address,
        String title,
        String gameId,
        String gameLabel,
        boolean optedIn,
        int attempt,
        RuleType ruleType,
        Map<String, String> context) {
    public ClaimedEmailDelivery {
        context = context == null ? Map.of() : Map.copyOf(context);
        gameLabel = gameLabel == null ? gameId : gameLabel;
    }

    public ClaimedEmailDelivery(UUID id, String address, String title, String gameId, boolean optedIn,
            int attempt, RuleType ruleType, Map<String, String> context) {
        this(id, address, title, gameId, gameId, optedIn, attempt, ruleType, context);
    }

    ClaimedEmailDelivery worded(String renderedTitle, String renderedGame) {
        return new ClaimedEmailDelivery(id, address, renderedTitle, gameId, renderedGame, optedIn, attempt,
                ruleType, context);
    }
}
