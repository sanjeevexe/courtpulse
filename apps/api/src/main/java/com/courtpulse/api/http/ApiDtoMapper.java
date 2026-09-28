package com.courtpulse.api.http;

import com.courtpulse.query.AlertReadModel;
import com.courtpulse.query.CanonicalEventReadModel;
import com.courtpulse.query.GameSnapshotReadModel;
import com.courtpulse.query.GameSummaryReadModel;
import com.courtpulse.query.KeysetPage;
import com.courtpulse.query.ProcessingHealthReadModel;

final class ApiDtoMapper {
    private ApiDtoMapper() {}

    static ApiDto.GamePage games(KeysetPage<GameSummaryReadModel> page) {
        return new ApiDto.GamePage(
                page.items().stream().map(ApiDtoMapper::game).toList(), page.nextCursor());
    }

    static ApiDto.GameSummary game(GameSummaryReadModel value) {
        return new ApiDto.GameSummary(
                value.gameId(), value.source(), value.homeTeamId(), value.awayTeamId(), value.status(),
                value.stateVersion(), value.homeScore(), value.awayScore(), value.period(),
                value.clockMillisRemaining(), value.lastAppliedSequence(), value.updatedAt(),
                value.stateChecksum(), value.dataStatus(), value.teams().homeTeamName(),
                value.teams().homeTeamAbbreviation(), value.teams().awayTeamName(),
                value.teams().awayTeamAbbreviation(), value.scheduledAt());
    }

    static ApiDto.GameSnapshot snapshot(GameSnapshotReadModel value) {
        return new ApiDto.GameSnapshot(
                value.gameId(), value.source(), value.homeTeamId(), value.awayTeamId(), value.status(),
                value.period(), value.clockMillisRemaining(), value.homeScore(), value.awayScore(),
                value.playerPoints(), value.stateVersion(), value.lastAppliedSequence(),
                value.stateChecksum(), value.recentEvents().stream()
                        .map(event -> new ApiDto.RecentEvent(
                                event.eventId(), event.sequence(), event.revision(), event.eventType(),
                                event.occurredAt(), new ApiDto.Score(event.homeScore(), event.awayScore()),
                                event.description()))
                        .toList(),
                value.updatedAt(), value.dataStatus(), value.teams().homeTeamName(),
                value.teams().homeTeamAbbreviation(), value.teams().awayTeamName(),
                value.teams().awayTeamAbbreviation(), value.scheduledAt(), value.playerNames());
    }

    static ApiDto.EventPage events(KeysetPage<CanonicalEventReadModel> page) {
        return new ApiDto.EventPage(page.items().stream().map(event -> new ApiDto.Event(
                event.eventId(), event.schemaVersion(), event.gameId(), event.source(),
                event.providerEventId(), event.sequence(), event.revision(), event.eventType(),
                event.period(), event.clockMillisRemaining(), event.occurredAt(), event.teamId(),
                event.participantIds(), new ApiDto.Score(event.scoreAfter().home(), event.scoreAfter().away()),
                event.points(), event.description())).toList(), page.nextCursor());
    }

    static ApiDto.AlertPage alerts(KeysetPage<AlertReadModel> page) {
        return new ApiDto.AlertPage(page.items().stream().map(alert -> new ApiDto.Alert(
                alert.ruleId(), alert.triggerKey(), alert.gameId(), alert.triggeringEventId(),
                alert.title(), alert.context(), alert.status(), alert.createdAt())).toList(), page.nextCursor());
    }

    static ApiDto.Processing processing(ProcessingHealthReadModel value) {
        return new ApiDto.Processing(
                value.pending(), value.publishing(), value.retryScheduled(), value.sent(), value.failed(),
                value.deferredNotifications(), value.oldestEligiblePendingAgeSeconds(), value.blockedGames(),
                value.processedEvents(), value.alerts(), value.apiTimestamp());
    }
}
