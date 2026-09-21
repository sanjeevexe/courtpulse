package com.courtpulse.api.realtime;

import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.RealtimeOutboxRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

public final class RealtimeOutboxPublisher {
    private final JdbcOutboxPublicationRepository repository;
    private final RealtimeHub hub;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String leaseOwner;
    private final Duration leaseDuration;
    private final int batchSize;
    private final int maximumAttempts;
    private final Counter failures;
    private final Counter leaseRecoveries;

    public RealtimeOutboxPublisher(
            JdbcOutboxPublicationRepository repository,
            RealtimeHub hub,
            TransactionTemplate transactions,
            Clock clock,
            String leaseOwner,
            Duration leaseDuration,
            int batchSize,
            int maximumAttempts,
            MeterRegistry meters) {
        if (batchSize < 1 || maximumAttempts < 1 || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Realtime publisher limits must be positive");
        }
        this.repository = repository;
        this.hub = hub;
        this.transactions = transactions;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
        this.leaseDuration = leaseDuration;
        this.batchSize = batchSize;
        this.maximumAttempts = maximumAttempts;
        this.failures = meters.counter("courtpulse.realtime.publication.failures");
        this.leaseRecoveries = meters.counter("courtpulse.realtime.publication.lease_recovered");
    }

    public RealtimePublicationResult publishBatch() {
        return publishBatch(RealtimePublicationFailureMode.NONE);
    }

    public RealtimePublicationResult publishBatch(RealtimePublicationFailureMode failureMode) {
        Instant claimTime = clock.instant();
        List<RealtimeOutboxRecord> records = transactions.execute(status ->
                repository.claimRealtime(leaseOwner, batchSize, claimTime, leaseDuration));
        if (records == null || records.isEmpty()) {
            return RealtimePublicationResult.empty();
        }
        RealtimePublicationResult result = RealtimePublicationResult.empty();
        for (RealtimeOutboxRecord record : records) {
            if (record.attempt() > 1) {
                leaseRecoveries.increment();
            }
            result = result.plus(publishOne(record, failureMode));
            failureMode = RealtimePublicationFailureMode.NONE;
        }
        return result;
    }

    private RealtimePublicationResult publishOne(
            RealtimeOutboxRecord record, RealtimePublicationFailureMode failureMode) {
        try {
            hub.publish(record);
            if (failureMode == RealtimePublicationFailureMode.AFTER_BROADCAST_BEFORE_COMPLETION) {
                throw new SimulatedRealtimeCrashException(
                        "Simulated crash after realtime broadcast for " + record.outboxId());
            }
            boolean owned = Boolean.TRUE.equals(transactions.execute(status ->
                    repository.markRealtimeSent(record.outboxId(), leaseOwner, clock.instant())));
            return new RealtimePublicationResult(1, owned ? 1 : 0, 0, 0, owned ? 0 : 1);
        } catch (SimulatedRealtimeCrashException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            failures.increment();
            Instant failedAt = clock.instant();
            if (record.attempt() < maximumAttempts) {
                Duration delay = retryDelay(record.attempt());
                boolean owned = Boolean.TRUE.equals(transactions.execute(status ->
                        repository.scheduleRealtimeRetry(
                                record.outboxId(), leaseOwner, failedAt, failedAt.plus(delay),
                                exception.getMessage())));
                return new RealtimePublicationResult(1, 0, owned ? 1 : 0, 0, owned ? 0 : 1);
            }
            boolean owned = Boolean.TRUE.equals(transactions.execute(status ->
                    repository.markRealtimeFailed(
                            record.outboxId(), leaseOwner, failedAt, exception.getMessage())));
            return new RealtimePublicationResult(1, 0, 0, owned ? 1 : 0, owned ? 0 : 1);
        }
    }

    private static Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(6, Math.max(0, attempt - 1));
        return Duration.ofMillis(Math.min(30_000, 500 * multiplier));
    }
}
