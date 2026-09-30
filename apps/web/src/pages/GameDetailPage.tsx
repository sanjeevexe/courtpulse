import { useState, type CSSProperties } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { uniqueAlerts, uniqueEvents, useAlerts, useEvents, useGameSnapshot, useMyAlerts } from '../api/hooks';
import { ApiError, followGame, listFollowedGames, unfollowGame } from '../api/client';
import { useAuth } from '../auth/useAuth';
import { DataStatusBadge } from '../components/DataStatusBadge';
import { isDataWarning } from '../lib/status';
import { GameStatus } from '../components/GameStatus';
import { Icon } from '../components/Icons';
import { Score } from '../components/Score';
import { TeamBadge } from '../components/TeamBadge';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';
import {
  alertDetails, formatClock, formatDateTime, formatPeriod, playerLabel, readableEventType, teamLabel,
} from '../lib/format';
import { teamCode, teamColor } from '../lib/teams';
import { connectionLabel, useGameRealtime } from '../realtime/hooks';
import { ReplayControls } from '../components/ReplayControls';

const PLAYER_PREVIEW = 8;

export function GameDetailPage() {
  const { gameId = '' } = useParams();
  const auth = useAuth();
  const queryClient = useQueryClient();
  const realtimeState = useGameRealtime(gameId);
  const snapshotQuery = useGameSnapshot(gameId, realtimeState === 'CONNECTED');
  const eventsQuery = useEvents(gameId);
  const alertsQuery = useAlerts(gameId);
  const events = uniqueEvents(eventsQuery.data);
  const alerts = uniqueAlerts(alertsQuery.data);
  const myAlertsQuery = useMyAlerts(auth.accessToken, auth.status === 'AUTHENTICATED');
  const myAlerts = (myAlertsQuery.data?.pages.flatMap((page) => page.items) ?? [])
    .filter((alert) => alert.gameId === gameId);
  const [showAllPlayers, setShowAllPlayers] = useState(false);
  const eventsBusy = eventsQuery.isFetchingNextPage || eventsQuery.isRefetching;
  const followedQuery = useQuery({
    queryKey: ['me', 'followed-games'],
    queryFn: () => listFollowedGames(auth.accessToken ?? ''),
    enabled: auth.status === 'AUTHENTICATED' && auth.accessToken !== null,
  });
  const followed = followedQuery.data?.items.some((item) => item.gameId === gameId) ?? false;
  const followMutation = useMutation({
    mutationFn: async () => {
      const token = auth.accessToken;
      if (!token) throw new ApiError('Sign in again to update My Games.', { kind: 'problem', status: 401 });
      if (followed) await unfollowGame(token, gameId);
      else await followGame(token, gameId);
    },
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ['me', 'followed-games'] }),
  });

  if (snapshotQuery.isPending) {
    return <LoadingState label="Loading game detail" variant="detail" />;
  }
  if (snapshotQuery.isError) {
    return (
      <>
        <Link className="back-link" to="/"><Icon name="arrow-left" size={16} />Back to scores</Link>
        <ErrorPanel error={snapshotQuery.error} onRetry={() => void snapshotQuery.refetch()} />
      </>
    );
  }

  const game = snapshotQuery.data;
  const players = Object.entries(game.playerPoints).sort(([, first], [, second]) => second - first);
  const shownPlayers = showAllPlayers ? players : players.slice(0, PLAYER_PREVIEW);
  const leaderPoints = players[0]?.[1] ?? 0;
  const final = game.status === 'FINAL';
  const awayName = teamLabel(game.awayTeamId, game.awayTeamName);
  const homeName = teamLabel(game.homeTeamId, game.homeTeamName);
  const teamColors: Record<string, string> = {
    [game.awayTeamId]: teamColor(teamCode(awayName, game.awayTeamAbbreviation)),
    [game.homeTeamId]: teamColor(teamCode(homeName, game.homeTeamAbbreviation)),
  };
  const alertCount = alerts.length + myAlerts.length;

  return (
    <article className="game-detail">
      <div className="detail-toolbar">
        <Link className="back-link" to="/"><Icon name="arrow-left" size={16} />Back to scores</Link>
        <div className="detail-toolbar__actions">
          <span className={`connection-state connection-state--${realtimeState.toLowerCase()}`} role="status">
            <span className="connection-state__dot" aria-hidden="true" />
            {connectionLabel(realtimeState)}
          </span>
          <button
            className="icon-button"
            disabled={snapshotQuery.isFetching}
            onClick={() => void snapshotQuery.refetch()}
            aria-label="Refresh game snapshot"
            title="Refresh score"
          >
            <Icon name="refresh" className={snapshotQuery.isFetching ? 'spin' : undefined} />
          </button>
          {auth.status === 'AUTHENTICATED' ? (
            <>
              <button
                className={`button button--secondary button--sm follow-button${followed ? ' is-followed' : ''}`}
                disabled={followedQuery.isPending || followMutation.isPending}
                onClick={() => followMutation.mutate()}
              >
                <Icon name={followed ? 'star-filled' : 'star'} size={16} />
                {followMutation.isPending ? 'Saving…' : followed ? 'Unfollow game' : 'Follow game'}
              </button>
              <Link className="button button--primary button--sm" to={`/my-rules?gameId=${encodeURIComponent(gameId)}`}>
                <Icon name="plus" size={16} />Create alert
              </Link>
            </>
          ) : null}
        </div>
      </div>
      {followMutation.isError ? (
        <p className="form-error" role="alert">
          {followMutation.error instanceof ApiError && followMutation.error.status === 403
            ? 'Your account is not permitted to change this follow.'
            : 'Your session expired or could not be verified. Sign in again and retry.'}
        </p>
      ) : null}
      <ReplayControls gameId={gameId} />

      <header className={`scoreboard scoreboard--${game.status.toLowerCase()}`}>
        <div className="scoreboard__topline">
          <GameStatus game={game} />
          {isDataWarning(game.dataStatus) ? <DataStatusBadge status={game.dataStatus} explain /> : null}
        </div>
        <div className="scoreboard__matchup">
          <section className={`score-team score-team--away${final && game.awayScore < game.homeScore ? ' is-dim' : ''}`}
            aria-label="Away team score">
            <TeamBadge name={awayName} abbreviation={game.awayTeamAbbreviation} size="lg" />
            <div className="score-team__name">
              <span>Away</span>
              <h1>{awayName}</h1>
            </div>
            <Score className="score-team__score" value={game.awayScore} />
          </section>
          <div className="score-divider" aria-hidden="true">at</div>
          <section className={`score-team score-team--home${final && game.homeScore < game.awayScore ? ' is-dim' : ''}`}
            aria-label="Home team score">
            <TeamBadge name={homeName} abbreviation={game.homeTeamAbbreviation} size="lg" />
            <div className="score-team__name">
              <span>Home</span>
              <h1>{homeName}</h1>
            </div>
            <Score className="score-team__score" value={game.homeScore} />
          </section>
        </div>
        <div className="scoreboard__footer">
          <span>Updated {formatDateTime(game.updatedAt)}</span>
          <span>Checkpoint v{game.stateVersion}</span>
        </div>
      </header>

      <div className="detail-layout">
        <section className="panel detail-layout__alerts" aria-labelledby="alerts-title">
          <div className="panel-heading">
            <h2 id="alerts-title">Triggered alerts</h2>
            {alertCount > 0 ? <span className="count-pill">{alertCount}</span> : null}
          </div>
          {alertsQuery.isError ? <ErrorPanel error={alertsQuery.error} onRetry={() => void alertsQuery.refetch()} /> : null}
          {myAlerts.length > 0 ? (
            <div className="alert-list" aria-label="Your alerts for this game">
              {myAlerts.map((alert) => (
                <article className="alert-card alert-card--mine" key={alert.id}>
                  <span className="alert-icon" aria-hidden="true"><Icon name="bell" size={16} /></span>
                  <div>
                    <span className="tag tag--accent">Your alert</span>
                    <h3>{alert.title}</h3>
                    <p>{alertDetails(alert.context, alert.ruleType)}</p>
                    <small>{formatDateTime(alert.createdAt)} · <Link to="/my-alerts">Delivery status</Link></small>
                  </div>
                </article>
              ))}
            </div>
          ) : null}
          {!alertsQuery.isPending && alertCount === 0 ? (
            <div className="inline-empty">
              <strong>No alerts yet</strong>
              <span>Notable moments will collect here.</span>
              {auth.status === 'AUTHENTICATED' ? (
                <Link className="text-link" to={`/my-rules?gameId=${encodeURIComponent(gameId)}`}>Set one for this game</Link>
              ) : null}
            </div>
          ) : null}
          {alerts.length > 0 ? (
            <div className="alert-list">
              {alerts.map((alert) => (
                <article className="alert-card" key={alert.triggerKey}>
                  <span className="alert-icon" aria-hidden="true"><Icon name="bell" size={16} /></span>
                  <div>
                    <h3>{alert.title}</h3>
                    <p>{alertDetails(alert.context)}</p>
                    <small>{formatDateTime(alert.createdAt)}</small>
                  </div>
                </article>
              ))}
            </div>
          ) : null}
        </section>

        <section className="panel detail-layout__plays" aria-labelledby="events-title">
          <div className="panel-heading">
            <h2 id="events-title">Play by play</h2>
            <span className="count-pill">Showing {events.length}</span>
          </div>
          {eventsQuery.isPending ? <LoadingState label="Loading play by play" variant="list" /> : null}
          {eventsQuery.isError ? <ErrorPanel error={eventsQuery.error} onRetry={() => void eventsQuery.refetch()} /> : null}
          {events.length > 0 ? (
            <ol className="event-list">
              {events.map((event) => (
                <li
                  className={`event-row${event.points > 0 ? ' event-row--score' : ''}`}
                  key={event.eventId}
                  style={event.teamId && teamColors[event.teamId]
                    ? { '--team': teamColors[event.teamId] } as CSSProperties : undefined}
                >
                  <span className="event-sequence">{event.sequence}</span>
                  <div className="event-time">
                    <strong>{formatPeriod(event.period)}</strong>
                    <span>{formatClock(event.clockMillisRemaining)}</span>
                  </div>
                  <div className="event-copy">
                    <strong>{event.description ?? readableEventType(event.eventType)}</strong>
                    <span>
                      {event.description && event.eventType !== 'PLAY_RECORDED' ? `${readableEventType(event.eventType)} · ` : ''}
                      {event.teamId
                        ? teamLabel(event.teamId, event.teamId === game.homeTeamId ? game.homeTeamName : event.teamId === game.awayTeamId ? game.awayTeamName : null)
                        : 'Game'}
                      {namedParticipants(event.participantIds, game.playerNames, Boolean(event.description))}
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
              disabled={eventsBusy}
              onClick={() => {
                if (!eventsBusy) void eventsQuery.fetchNextPage({ cancelRefetch: false });
              }}
            >
              {eventsQuery.isFetchingNextPage
                ? 'Loading possessions…'
                : eventsQuery.isRefetching
                  ? 'Refreshing possessions…'
                  : 'Load more possessions'}
            </button>
          ) : null}
        </section>

        <section className="panel detail-layout__players" aria-labelledby="players-title">
          <div className="panel-heading">
            <h2 id="players-title">Player totals</h2>
          </div>
          {players.length > 0 ? (
            <ol className="player-list">
              {shownPlayers.map(([player, points]) => (
                <li className="player-row" key={player}>
                  <span className="player-row__name">{playerLabel(player, game.playerNames)}</span>
                  <span className="player-row__bar" aria-hidden="true">
                    <span style={{ transform: `scaleX(${String(leaderPoints > 0 ? points / leaderPoints : 0)})` }} />
                  </span>
                  <strong>{points}<small> PTS</small></strong>
                </li>
              ))}
            </ol>
          ) : <p className="muted">Player totals are not available yet.</p>}
          {players.length > PLAYER_PREVIEW ? (
            <button className="button button--quiet button--full" onClick={() => setShowAllPlayers((value) => !value)}>
              {showAllPlayers ? 'Show top players' : `Show all ${String(players.length)} players`}
            </button>
          ) : null}
        </section>
      </div>
    </article>
  );
}

/** Named players only; an unnamed ID adds nothing when the play's own text already names them. */
function namedParticipants(ids: string[], names: Record<string, string>, described: boolean): string {
  const labels = ids.filter((id) => !described || names[id]).map((id) => playerLabel(id, names));
  return labels.length > 0 ? ` · ${labels.join(', ')}` : '';
}
