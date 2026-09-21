import { Link, useParams } from 'react-router-dom';
import { uniqueAlerts, uniqueEvents, useAlerts, useEvents, useGameSnapshot } from '../api/hooks';
import { DataStatusBadge } from '../components/DataStatusBadge';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';
import { formatClock, formatDateTime, formatPeriod, readableEventType, shortTeam } from '../lib/format';

export function GameDetailPage() {
  const { gameId = '' } = useParams();
  const snapshotQuery = useGameSnapshot(gameId);
  const eventsQuery = useEvents(gameId);
  const alertsQuery = useAlerts(gameId);
  const events = uniqueEvents(eventsQuery.data);
  const alerts = uniqueAlerts(alertsQuery.data);

  if (snapshotQuery.isPending) {
    return <LoadingState label="Loading game detail" />;
  }
  if (snapshotQuery.isError) {
    return (
      <>
        <Link className="back-link" to="/">← Back to game slate</Link>
        <ErrorPanel error={snapshotQuery.error} onRetry={() => void snapshotQuery.refetch()} />
      </>
    );
  }

  const game = snapshotQuery.data;
  const players = Object.entries(game.playerPoints).sort(([, first], [, second]) => second - first);

  return (
    <article className="game-detail">
      <div className="detail-toolbar">
        <Link className="back-link" to="/">← Back to game slate</Link>
        <button
          className="button button--quiet"
          disabled={snapshotQuery.isFetching}
          onClick={() => void snapshotQuery.refetch()}
          aria-label="Refresh game snapshot"
        >
          {snapshotQuery.isFetching ? 'Refreshing…' : 'Refresh score'}
        </button>
      </div>

      <header className="scoreboard">
        <div className="scoreboard__topline">
          <div>
            <span className="game-state">{game.status}</span>
            <span>{formatPeriod(game.period, game.status)}</span>
            {game.status === 'LIVE' ? <strong>{formatClock(game.clockMillisRemaining)}</strong> : null}
          </div>
          <DataStatusBadge status={game.dataStatus} explain />
        </div>
        <div className="scoreboard__matchup">
          <section className="score-team score-team--away" aria-label="Away team score">
            <span>Away</span>
            <h1>{shortTeam(game.awayTeamId)}</h1>
            <strong>{game.awayScore}</strong>
          </section>
          <div className="score-divider" aria-hidden="true"><span>at</span></div>
          <section className="score-team score-team--home" aria-label="Home team score">
            <span>Home</span>
            <h1>{shortTeam(game.homeTeamId)}</h1>
            <strong>{game.homeScore}</strong>
          </section>
        </div>
        <div className="scoreboard__footer">
          <span>Updated {formatDateTime(game.updatedAt)}</span>
          <span>Checkpoint v{game.stateVersion}</span>
        </div>
      </header>

      <div className="detail-grid">
        <section className="panel" aria-labelledby="players-title">
          <div className="panel-heading">
            <div><p className="eyebrow">Box pulse</p><h2 id="players-title">Player totals</h2></div>
          </div>
          {players.length > 0 ? (
            <div className="player-list">
              {players.map(([player, points]) => (
                <div className="player-row" key={player}>
                  <span>{player.replaceAll('_', ' ')}</span>
                  <strong>{points}<small> PTS</small></strong>
                </div>
              ))}
            </div>
          ) : <p className="muted">Player totals are not available yet.</p>}
        </section>

        <section className="panel" aria-labelledby="recent-title">
          <div className="panel-heading">
            <div><p className="eyebrow">At a glance</p><h2 id="recent-title">Recent possessions</h2></div>
          </div>
          <ol className="recent-list">
            {game.recentEvents.map((event) => (
              <li key={event.eventId}>
                <span>#{event.sequence}</span>
                <strong>{readableEventType(event.eventType)}</strong>
                <small>{event.scoreAfter.away}–{event.scoreAfter.home}</small>
              </li>
            ))}
          </ol>
        </section>
      </div>

      <section className="panel panel--wide" aria-labelledby="events-title">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Possession log</p>
            <h2 id="events-title">Play by play</h2>
          </div>
          <span className="count-pill">{events.length} loaded</span>
        </div>
        {eventsQuery.isPending ? <LoadingState label="Loading play by play" /> : null}
        {eventsQuery.isError ? <ErrorPanel error={eventsQuery.error} onRetry={() => void eventsQuery.refetch()} /> : null}
        {events.length > 0 ? (
          <ol className="event-list">
            {events.map((event) => (
              <li className="event-row" key={event.eventId}>
                <span className="event-sequence">{event.sequence}</span>
                <div className="event-time">
                  <strong>{formatPeriod(event.period)}</strong>
                  <span>{formatClock(event.clockMillisRemaining)}</span>
                </div>
                <div className="event-copy">
                  <strong>{readableEventType(event.eventType)}</strong>
                  <span>
                    {event.teamId ? shortTeam(event.teamId) : 'Game'}
                    {event.participantIds.length > 0 ? ` · ${event.participantIds.join(', ')}` : ''}
                    {event.points > 0 ? ` · ${String(event.points)} pts` : ''}
                  </span>
                </div>
                <strong className="event-score">{event.scoreAfter.away}–{event.scoreAfter.home}</strong>
              </li>
            ))}
          </ol>
        ) : null}
        {eventsQuery.hasNextPage ? (
          <button
            className="button button--secondary button--full"
            disabled={eventsQuery.isFetchingNextPage}
            onClick={() => void eventsQuery.fetchNextPage()}
          >
            {eventsQuery.isFetchingNextPage ? 'Loading possessions…' : 'Load more possessions'}
          </button>
        ) : null}
      </section>

      <section className="panel panel--wide" aria-labelledby="alerts-title">
        <div className="panel-heading">
          <div><p className="eyebrow">Moments</p><h2 id="alerts-title">Triggered alerts</h2></div>
          <span className="count-pill">{alerts.length}</span>
        </div>
        {alertsQuery.isError ? <ErrorPanel error={alertsQuery.error} onRetry={() => void alertsQuery.refetch()} /> : null}
        {!alertsQuery.isPending && alerts.length === 0 ? (
          <div className="inline-empty"><strong>No alerts yet</strong><span>Notable moments will collect here.</span></div>
        ) : null}
        <div className="alert-list">
          {alerts.map((alert) => (
            <article className="alert-card" key={alert.triggerKey}>
              <span className="alert-icon" aria-hidden="true">!</span>
              <div>
                <h3>{alert.title}</h3>
                <p>{Object.entries(alert.context).map(([key, value]) => `${key}: ${value}`).join(' · ')}</p>
                <small>Rule {alert.ruleId} · Event {alert.triggeringEventId} · {formatDateTime(alert.createdAt)}</small>
              </div>
            </article>
          ))}
        </div>
      </section>
    </article>
  );
}
