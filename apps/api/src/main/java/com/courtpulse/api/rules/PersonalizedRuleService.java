package com.courtpulse.api.rules;

import com.courtpulse.api.http.RuleApiDto;
import com.courtpulse.domain.alert.CloseGameRule;
import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.alert.RuleType;
import com.courtpulse.domain.alert.ScoringRunRule;
import com.courtpulse.persistence.AlertRuleCreation;
import com.courtpulse.persistence.JdbcAlertRuleRepository;
import com.courtpulse.persistence.JdbcUserOwnershipRepository;
import com.courtpulse.persistence.NewAlertRule;
import com.courtpulse.persistence.AlertWording;
import com.courtpulse.persistence.JdbcDisplayNames;
import com.courtpulse.persistence.OwnedAlertRecord;
import com.courtpulse.persistence.RuleEngineMetrics;
import com.courtpulse.persistence.StoredAlertRule;
import com.courtpulse.query.GameNotFoundException;
import com.courtpulse.query.InvalidCursorException;
import com.courtpulse.query.OpaqueCursorCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public final class PersonalizedRuleService {
    public static final int MAX_RULES_PER_USER = 50;
    public static final int MAX_RULES_PER_GAME = 1_000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcAlertRuleRepository rules;
    private final JdbcUserOwnershipRepository users;
    private final OpaqueCursorCodec cursors;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final RuleEngineMetrics metrics;
    private final JdbcDisplayNames names;

    public PersonalizedRuleService(
            JdbcAlertRuleRepository rules,
            JdbcUserOwnershipRepository users,
            OpaqueCursorCodec cursors,
            TransactionTemplate transactions,
            Clock clock,
            RuleEngineMetrics metrics,
            JdbcDisplayNames names) {
        this.names = names;
        this.rules = rules;
        this.users = users;
        this.cursors = cursors;
        this.transactions = transactions;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Creation create(
            String owner, String idempotencyKey, RuleApiDto.CreateRuleRequest request) {
        requireIdempotencyKey(idempotencyKey);
        NewAlertRule proposed = normalize(request);
        return transactions.execute(status -> {
            Instant now = clock.instant();
            users.touchUser(owner, now);
            rules.lockOwner(owner);
            var replay = rules.findOwnedByRequestId(owner, idempotencyKey);
            if (replay.isPresent()) {
                if (!sameDefinition(replay.get(), proposed)) {
                    throw new RuleConflictException(
                            "The idempotency key was already used for a different rule request.");
                }
                return new Creation(map(replay.get()), false);
            }
            if (!rules.lockGame(proposed.gameId())) {
                throw new GameNotFoundException(proposed.gameId());
            }
            if (rules.countOwnedRules(owner) >= MAX_RULES_PER_USER) {
                metrics.quotaRejected();
                throw new RuleQuotaExceededException(MAX_RULES_PER_USER);
            }
            if (rules.countOwnedGameRules(proposed.gameId()) >= MAX_RULES_PER_GAME) {
                metrics.quotaRejected();
                throw new RuleQuotaExceededException("The game alert-rule limit of "
                        + MAX_RULES_PER_GAME + " has been reached.");
            }
            AlertRuleCreation created = rules.create(owner, idempotencyKey, proposed, now);
            if (!sameDefinition(created.rule(), proposed)) {
                throw new RuleConflictException(
                        "The idempotency key was already used for a different rule request.");
            }
            return new Creation(map(created.rule()), created.created());
        });
    }

    public RuleApiDto.Rule get(String owner, UUID id) {
        return transactions.execute(status -> {
            users.touchUser(owner, clock.instant());
            return map(rules.findOwned(owner, id).orElseThrow(RuleNotFoundException::new));
        });
    }

    public RuleApiDto.RulePage list(String owner, Integer requestedLimit, String cursor) {
        int limit = pageSize(requestedLimit);
        Cursor after = decode(cursor, "rules", owner);
        return transactions.execute(status -> {
            users.touchUser(owner, clock.instant());
            List<StoredAlertRule> fetched = rules.listOwned(
                    owner,
                    after == null ? null : after.createdAt(),
                    after == null ? null : after.id(),
                    limit + 1);
            boolean more = fetched.size() > limit;
            List<StoredAlertRule> page = more ? fetched.subList(0, limit) : fetched;
            String next = more ? encode("rules", owner, page.getLast().createdAt(), page.getLast().id()) : null;
            JdbcDisplayNames.Names known = namesFor(page);
            return new RuleApiDto.RulePage(page.stream().map(rule -> map(rule, known)).toList(), next);
        });
    }

    public RuleApiDto.Rule updateEnabled(
            String owner, UUID id, RuleApiDto.UpdateRuleRequest request) {
        return transactions.execute(status -> {
            users.touchUser(owner, clock.instant());
            StoredAlertRule current = rules.findOwned(owner, id)
                    .orElseThrow(RuleNotFoundException::new);
            if (current.version() != request.version()) {
                throw new RuleConflictException("The alert rule was changed by another request.");
            }
            return map(rules.updateEnabled(owner, id, request.enabled(), request.version(), clock.instant())
                    .orElseThrow(() -> new RuleConflictException(
                            "The alert rule was changed by another request.")));
        });
    }

    public void delete(String owner, UUID id) {
        transactions.executeWithoutResult(status -> {
            users.touchUser(owner, clock.instant());
            if (!rules.deleteOwned(owner, id)) {
                throw new RuleNotFoundException();
            }
        });
    }

    public RuleApiDto.OwnedAlertPage alerts(String owner, Integer requestedLimit, String cursor) {
        int limit = pageSize(requestedLimit);
        Cursor after = decode(cursor, "owned-alerts", owner);
        return transactions.execute(status -> {
            users.touchUser(owner, clock.instant());
            List<OwnedAlertRecord> fetched = rules.listOwnedAlerts(
                    owner,
                    after == null ? null : after.createdAt(),
                    after == null ? null : after.id(),
                    limit + 1);
            boolean more = fetched.size() > limit;
            List<OwnedAlertRecord> page = more ? fetched.subList(0, limit) : fetched;
            String next = more
                    ? encode("owned-alerts", owner, page.getLast().createdAt(), page.getLast().id())
                    : null;
            JdbcDisplayNames.Names known = names.lookup(
                    page.stream().map(alert -> alert.context().get("playerId")).toList(),
                    page.stream().map(alert -> alert.context().get("teamId")).toList(),
                    page.stream().map(OwnedAlertRecord::gameId).toList());
            return new RuleApiDto.OwnedAlertPage(page.stream().map(alert -> mapAlert(alert, known)).toList(), next);
        });
    }

    private NewAlertRule normalize(RuleApiDto.CreateRuleRequest request) {
        boolean enabled = request.enabled() == null || request.enabled();
        if (request instanceof RuleApiDto.PlayerPointsRequest value) {
            requireType(value.type(), RuleType.PLAYER_POINTS);
            validate(() -> new PlayerMilestoneRule(
                    "validation", "validation-owner", value.playerId(), value.pointsThreshold()));
            return new NewAlertRule(value.gameId(), value.type(), enabled, value.playerId(), null,
                    value.pointsThreshold(), null, null, null);
        }
        if (request instanceof RuleApiDto.CloseGameRequest value) {
            requireType(value.type(), RuleType.CLOSE_GAME);
            validate(() -> new CloseGameRule(
                    "validation", "validation-owner", value.maximumMargin(), value.eligiblePeriod(),
                    value.maximumClockMillisRemaining()));
            return new NewAlertRule(value.gameId(), value.type(), enabled, null, null, null,
                    value.maximumMargin(), value.eligiblePeriod(), value.maximumClockMillisRemaining());
        }
        if (request instanceof RuleApiDto.ScoringRunRequest value) {
            requireType(value.type(), RuleType.SCORING_RUN);
            validate(() -> new ScoringRunRule(
                    "validation", "validation-owner", value.teamId(), value.pointsThreshold()));
            return new NewAlertRule(value.gameId(), value.type(), enabled, null, value.teamId(),
                    value.pointsThreshold(), null, null, null);
        }
        throw new RuleValidationException("Unsupported alert rule type.");
    }

    private RuleApiDto.Rule map(StoredAlertRule rule) {
        return map(rule, namesFor(List.of(rule)));
    }

    private JdbcDisplayNames.Names namesFor(List<StoredAlertRule> page) {
        return names.lookup(page.stream().map(StoredAlertRule::playerId).toList(),
                page.stream().map(StoredAlertRule::teamId).toList(),
                page.stream().map(StoredAlertRule::gameId).toList());
    }

    private static RuleApiDto.Rule map(StoredAlertRule rule, JdbcDisplayNames.Names known) {
        String game = known.games().get(rule.gameId());
        return switch (rule.type()) {
            case PLAYER_POINTS -> new RuleApiDto.PlayerPointsRule(
                    rule.id(), rule.type(), rule.gameId(), rule.enabled(), rule.playerId(),
                    rule.pointsThreshold(), rule.version(), rule.createdAt(), rule.updatedAt(),
                    known.players().get(rule.playerId()), game);
            case CLOSE_GAME -> new RuleApiDto.CloseGameRule(
                    rule.id(), rule.type(), rule.gameId(), rule.enabled(), rule.maximumMargin(),
                    rule.eligiblePeriod(), rule.maximumClockMillisRemaining(), rule.version(),
                    rule.createdAt(), rule.updatedAt(), game);
            case SCORING_RUN -> new RuleApiDto.ScoringRunRule(
                    rule.id(), rule.type(), rule.gameId(), rule.enabled(), rule.teamId(),
                    rule.pointsThreshold(), rule.version(), rule.createdAt(), rule.updatedAt(),
                    known.teams().get(rule.teamId()), game);
        };
    }

    private static RuleApiDto.OwnedAlert mapAlert(OwnedAlertRecord alert, JdbcDisplayNames.Names known) {
        return new RuleApiDto.OwnedAlert(
                alert.id(), alert.ruleId(), alert.ruleType(), alert.gameId(), alert.triggeringEventId(),
                AlertWording.title(alert.ruleType(), alert.context(), alert.title(), known),
                alert.context(), alert.status(), alert.createdAt(), known.games().get(alert.gameId()));
    }

    private static boolean sameDefinition(StoredAlertRule stored, NewAlertRule proposed) {
        return proposed.requestFingerprint().equals(stored.requestFingerprint());
    }

    private static void requireType(RuleType actual, RuleType expected) {
        if (actual != expected) {
            throw new RuleValidationException("Rule discriminator does not match its parameters.");
        }
    }

    private static void validate(Runnable validation) {
        try {
            validation.run();
        } catch (IllegalArgumentException exception) {
            throw new RuleValidationException(exception.getMessage());
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw new RuleValidationException(
                    "Idempotency-Key must contain 1 to 100 safe ASCII characters.");
        }
    }

    private static int pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requested < 1 || requested > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return requested;
    }

    private Cursor decode(String cursor, String type, String owner) {
        if (cursor == null) {
            return null;
        }
        List<String> keys = cursors.decode(cursor, type, owner, 2);
        try {
            return new Cursor(Instant.parse(keys.get(0)), UUID.fromString(keys.get(1)));
        } catch (IllegalArgumentException exception) {
            throw new InvalidCursorException("Cursor is malformed", exception);
        }
    }

    private String encode(String type, String owner, Instant createdAt, UUID id) {
        return cursors.encode(type, owner, List.of(createdAt.toString(), id.toString()));
    }

    public record Creation(RuleApiDto.Rule rule, boolean created) {}
    private record Cursor(Instant createdAt, UUID id) {}
}
