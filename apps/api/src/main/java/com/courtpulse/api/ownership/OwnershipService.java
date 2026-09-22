package com.courtpulse.api.ownership;

import com.courtpulse.persistence.FollowedGameRecord;
import com.courtpulse.persistence.JdbcUserOwnershipRepository;
import com.courtpulse.query.GameNotFoundException;
import java.time.Clock;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

public final class OwnershipService {
    private final JdbcUserOwnershipRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OwnershipService(
            JdbcUserOwnershipRepository repository,
            TransactionTemplate transactions,
            Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void observeUser(String subject) {
        transactions.executeWithoutResult(status -> repository.touchUser(subject, clock.instant()));
    }

    public List<FollowedGameRecord> followedGames(String subject) {
        return transactions.execute(status -> {
            repository.touchUser(subject, clock.instant());
            return repository.followedGames(subject);
        });
    }

    public boolean follow(String subject, String gameId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            repository.touchUser(subject, clock.instant());
            requireGame(gameId);
            return repository.follow(subject, gameId, clock.instant());
        }));
    }

    public boolean unfollow(String subject, String gameId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            repository.touchUser(subject, clock.instant());
            requireGame(gameId);
            return repository.unfollow(subject, gameId);
        }));
    }

    private void requireGame(String gameId) {
        if (!repository.gameExists(gameId)) {
            throw new GameNotFoundException(gameId);
        }
    }
}
