package com.courtpulse.persistence;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.alert.AlertRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameReducer;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.replay.StateChecksum;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionTemplate;

public final class DurableGameProcessor {
    private final JdbcGameProcessingRepository repository;
    private final JdbcOutboxRepository outbox;
    private final TransactionTemplate transactions;
    private final List<? extends AlertRule> rules;
    private final Clock clock;

    public DurableGameProcessor(
            JdbcGameProcessingRepository repository,
            JdbcOutboxRepository outbox,
            TransactionTemplate transactions,
            List<? extends AlertRule> rules,
            Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.transactions = transactions;
        this.rules = List.copyOf(rules);
        this.clock = clock;
    }

    public DurableProcessingResult processEvent(String eventId) {
        return processEvent(eventId, FailureMode.NONE);
    }

    public DurableProcessingResult processEvent(String eventId, FailureMode failureMode) {
        DurableProcessingResult result = transactions.execute(status -> processInTransaction(eventId, failureMode));
        if (failureMode == FailureMode.AFTER_COMMIT) {
            throw new SimulatedProcessingFailureException(
                    "Simulated process failure after commit for event " + eventId);
        }
        return result;
    }

    private DurableProcessingResult processInTransaction(String eventId, FailureMode failureMode) {
        CanonicalEvent event = repository.findEvent(eventId);
        GameState previousState = repository.lockCheckpoint(event.gameId());

        // These checks intentionally happen before durable duplicate suppression.
        GameReducer.validateEventMetadata(previousState, event);
        if (repository.isProcessed(event)) {
            return new DurableProcessingResult(
                    false,
                    previousState,
                    List.of(),
                    StateChecksum.sha256(previousState));
        }

        GameState nextState = GameReducer.apply(previousState, event);
        String checksum = StateChecksum.sha256(nextState);
        Instant now = clock.instant();
        repository.saveCheckpoint(nextState, checksum, now);
        if (!repository.insertProcessed(event, now)) {
            throw new IllegalStateException("Processed-event uniqueness conflict for " + event.identity());
        }
        outbox.insert(
                "GAME_STATE_UPDATED:" + event.eventId(),
                "GAME",
                event.gameId(),
                "GAME_STATE_UPDATED",
                Map.of(
                        "eventId", event.eventId(),
                        "gameId", event.gameId(),
                        "sequence", event.sequence(),
                        "stateChecksum", checksum),
                now);

        List<Alert> createdAlerts = new ArrayList<>();
        for (AlertRule rule : rules) {
            rule.evaluate(previousState, nextState, event).ifPresent(alert -> {
                if (repository.insertAlert(alert, now)) {
                    createdAlerts.add(alert);
                    outbox.insert(
                            "ALERT_CREATED:" + alert.triggerKey(),
                            "ALERT",
                            alert.triggerKey(),
                            "ALERT_CREATED",
                            Map.of(
                                    "gameId", alert.gameId(),
                                    "ruleId", alert.ruleId(),
                                    "triggerKey", alert.triggerKey(),
                                    "triggeringEventId", alert.triggeringEventId()),
                            now);
                }
            });
        }

        if (failureMode == FailureMode.BEFORE_COMMIT) {
            throw new SimulatedProcessingFailureException(
                    "Simulated process failure before commit for event " + eventId);
        }
        return new DurableProcessingResult(true, nextState, createdAlerts, checksum);
    }
}
