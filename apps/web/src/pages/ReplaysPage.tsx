import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { startReplay, type ReplayGame, type ReplaySession } from '../api/client';
import { useReplayGames, useReplaySessions } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { EmptyState } from '../components/EmptyState';
import { Icon } from '../components/Icons';
import { LoadingState } from '../components/LoadingState';
import { PageHeader } from '../components/PageHeader';
import { ProgressBar } from '../components/ProgressBar';
import { SegmentedControl } from '../components/SegmentedControl';
import { TeamBadge } from '../components/TeamBadge';
import { playoffTitle } from '../lib/playoffs';
import { NBA_TEAM_CODES } from '../lib/teams';
import { REPLAY_SPEEDS, formatReplayDate, replayLength, waitForReplayGame } from '../lib/replay';

export function ReplaysPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [team, setTeam] = useState<string | null>(null);
  const [speed, setSpeed] = useState<number>(30);
  const games = useReplayGames(team);
  const sessions = useReplaySessions();
  const start = useMutation({
    mutationFn: async (game: ReplayGame) => {
      const session = await startReplay(auth.accessToken ?? '', game.nbaGameId, speed);
      await waitForReplayGame(session.gameId);
      return session;
    },
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: ['replays', 'sessions'] });
      await queryClient.invalidateQueries({ queryKey: ['me', 'replays'] });
      void navigate(`/games/${encodeURIComponent(session.gameId)}`);
    },
  });
  const items = games.data?.pages.flatMap((page) => page.items) ?? [];
  const active = sessions.data?.items.filter((session) => session.status !== 'FINISHED') ?? [];

  return (
    <>
      <PageHeader
        eyebrow="Real games"
        title="Replays"
        lede="Pick a finished NBA game and CourtPulse plays it back play by play, as if it were live. The scoreboard, your alert rules, and email all react just as they would tonight."
      />

      {active.length > 0 ? (
        <section className="panel now-replaying" aria-labelledby="now-replaying">
          <div className="panel-heading">
            <h2 id="now-replaying"><span className="live-dot" aria-hidden="true" />Now replaying</h2>
            <span className="count-pill">{active.length}</span>
          </div>
          <ul className="replay-sessions">
            {active.map((session) => <SessionRow key={session.sessionId} session={session} />)}
          </ul>
        </section>
      ) : null}

      <div className="toolbar">
        <label className="field field--inline">
          <span>Team</span>
          <select value={team ?? ''} onChange={(event) => setTeam(event.target.value || null)}>
            <option value="">All teams</option>
            {NBA_TEAM_CODES.map((code) => <option key={code} value={code}>{code}</option>)}
          </select>
        </label>
        <div className="field field--inline">
          <span aria-hidden="true">Speed</span>
          <SegmentedControl label="Speed" value={speed} onChange={setSpeed}
            options={REPLAY_SPEEDS.map((value) => ({ value, label: `${String(value)}×` }))} />
        </div>
        {!auth.enabled ? <p className="muted">Starting a replay needs sign-in.</p> : null}
      </div>
      {start.isError ? <p className="form-error" role="alert">{start.error.message}</p> : null}

      {games.isPending ? <LoadingState label="Loading real games" /> : null}
      {games.isError ? <ErrorPanel error={games.error} onRetry={() => void games.refetch()} /> : null}
      {!games.isPending && !games.isError && items.length === 0 ? (
        <EmptyState
          title={`No replays available${team ? ` for ${team}` : ''} yet`}
          body={<>The demo imports the 2026 playoffs on first start; otherwise run <code>make replay-import</code>.</>}
        />
      ) : null}
      {items.length > 0 ? (
        <section className="game-grid" aria-label="Real games available for replay">
          {items.map((game) => {
            const starting = start.isPending && start.variables.nbaGameId === game.nbaGameId;
            const awayWon = game.awayScore > game.homeScore;
            const title = playoffTitle(game.nbaGameId, game.homeTeamAbbreviation);
            return (
              <article key={game.nbaGameId} className={`game-card replay-card${starting ? ' is-starting' : ''}`}>
                <div className="game-card__meta">
                  <span className="game-status game-status--final">
                    <span className="final-label">
                      {game.periods > 4 ? `Final/${game.periods === 5 ? 'OT' : `${String(game.periods - 4)}OT`}` : 'Final'}
                    </span>
                  </span>
                  {title ? <span className="game-card__title">{title}</span> : null}
                </div>
                <div className="matchup">
                  <ReplayTeam name={game.awayTeamName} abbreviation={game.awayTeamAbbreviation}
                    score={game.awayScore} dim={!awayWon} />
                  <ReplayTeam name={game.homeTeamName} abbreviation={game.homeTeamAbbreviation}
                    score={game.homeScore} dim={awayWon} />
                </div>
                <div className="game-card__footer">
                  <span>
                    {formatReplayDate(game.gameDate)} · {game.plays} plays · {replayLength(game.durationSeconds, speed)} at {speed}×
                  </span>
                  {auth.status === 'AUTHENTICATED' ? (
                    <button className="button button--secondary button--sm" disabled={start.isPending}
                      onClick={() => start.mutate(game)}>
                      {starting ? 'Starting replay…' : <><Icon name="play" size={12} />Replay</>}
                    </button>
                  ) : auth.enabled ? (
                    <button className="button button--secondary button--sm" disabled={auth.signInPending}
                      onClick={() => void auth.signIn('/replays')}>Sign in to replay</button>
                  ) : null}
                </div>
              </article>
            );
          })}
        </section>
      ) : null}
      {games.hasNextPage ? (
        <div className="load-more-row">
          <button className="button button--secondary" disabled={games.isFetchingNextPage}
            onClick={() => void games.fetchNextPage()}>
            {games.isFetchingNextPage ? 'Loading…' : 'Load more games'}
          </button>
        </div>
      ) : null}
      <p className="muted replay-provenance">
        Play-by-play from NBA.com as republished by the open nba_data project. Unofficial and used for personal,
        non-commercial demonstration; CourtPulse is not affiliated with the NBA.
      </p>
    </>
  );
}

function ReplayTeam({ name, abbreviation, score, dim }: {
  name: string; abbreviation: string; score: number; dim: boolean;
}) {
  return (
    <div className={`team-line${dim ? ' team-line--dim' : ''}`}>
      <TeamBadge name={name} abbreviation={abbreviation} />
      <span className="team-line__names"><strong>{name}</strong></span>
      <strong className="score">{score}</strong>
    </div>
  );
}

function SessionRow({ session }: { session: ReplaySession }) {
  const title = playoffTitle(session.nbaGameId, session.homeTeamName);
  return (
    <li>
      <Link to={`/games/${encodeURIComponent(session.gameId)}`}>
        <TeamBadge name={session.awayTeamName} size="sm" />
        <TeamBadge name={session.homeTeamName} size="sm" />
        <span className="replay-sessions__text">
          <span>{session.awayTeamName} at {session.homeTeamName}</span>
          {title ? <small>{title}</small> : null}
        </span>
      </Link>
      <span className="muted">{session.status === 'PAUSED' ? 'Paused' : `${String(session.speed)}×`}</span>
      <ProgressBar max={session.totalPlays} value={session.playsReleased} label="Replay progress" />
    </li>
  );
}
