import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { startReplay, type ReplayGame, type ReplaySession } from '../api/client';
import { useReplayGames, useReplaySessions } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { ErrorPanel } from '../components/ErrorPanel';
import { LoadingState } from '../components/LoadingState';
import { REPLAY_SPEEDS, formatReplayDate, replayLength, replayProgress, waitForReplayGame } from '../lib/replay';

const TEAMS = ['ATL', 'BOS', 'BKN', 'CHA', 'CHI', 'CLE', 'DAL', 'DEN', 'DET', 'GSW', 'HOU', 'IND', 'LAC', 'LAL',
  'MEM', 'MIA', 'MIL', 'MIN', 'NOP', 'NYK', 'OKC', 'ORL', 'PHI', 'PHX', 'POR', 'SAC', 'SAS', 'TOR', 'UTA', 'WAS'];

export function ReplaysPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [team, setTeam] = useState<string | null>(null);
  const [speed, setSpeed] = useState(30);
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
      <section className="slate-heading">
        <div>
          <p className="eyebrow">Real games</p>
          <h1>Replay a real game</h1>
          <p>
            Pick a finished NBA game and CourtPulse plays it back play by play as if it were live: the scoreboard,
            your alert rules, and email all react exactly as they would tonight. Choose a speed, then pause or
            skip ahead whenever you like.
          </p>
        </div>
      </section>

      {active.length > 0 ? (
        <section className="panel panel--wide" aria-labelledby="now-replaying">
          <div className="panel-heading"><h2 id="now-replaying">Now replaying</h2>
            <span className="count-pill">{active.length}</span></div>
          <ul className="replay-sessions">
            {active.map((session) => <SessionRow key={session.sessionId} session={session} />)}
          </ul>
        </section>
      ) : null}

      <div className="replay-toolbar">
        <label>Team
          <select value={team ?? ''} onChange={(event) => setTeam(event.target.value || null)}>
            <option value="">All teams</option>
            {TEAMS.map((code) => <option key={code} value={code}>{code}</option>)}
          </select>
        </label>
        <label>Speed
          <select value={speed} onChange={(event) => setSpeed(Number(event.target.value))}>
            {REPLAY_SPEEDS.map((value) => <option key={value} value={value}>{value}× </option>)}
          </select>
        </label>
        {!auth.enabled ? <p className="muted">Starting a replay needs sign-in, which is off in this setup.</p> : null}
      </div>
      {start.isError ? <p className="form-error" role="alert">{start.error.message}</p> : null}

      {games.isPending ? <LoadingState label="Loading real games" /> : null}
      {games.isError ? <ErrorPanel error={games.error} onRetry={() => void games.refetch()} /> : null}
      {!games.isPending && !games.isError && items.length === 0 ? (
        <section className="empty-state">
          <h2>No real games imported{team ? ` for ${team}` : ''}</h2>
          <p>The demo imports the 2026 playoffs on first start; otherwise run <code>make replay-import</code>.</p>
        </section>
      ) : null}
      {items.length > 0 ? (
        <section className="game-grid" aria-label="Real games available for replay">
          {items.map((game) => (
            <article key={game.nbaGameId} className="game-card replay-card">
              <div className="game-card__meta">
                <span>{formatReplayDate(game.gameDate)}</span>
                <span>{game.periods > 4 ? `Final/${game.periods === 5 ? 'OT' : `${String(game.periods - 4)}OT`}` : 'Final'}</span>
              </div>
              <div className="replay-card__teams">
                <div><span>{game.awayTeamName}</span><strong>{game.awayScore}</strong></div>
                <div><span>{game.homeTeamName}</span><strong>{game.homeScore}</strong></div>
              </div>
              <div className="game-card__footer">
                <span>{game.plays} plays · {replayLength(game.durationSeconds, speed)} at {speed}×</span>
                {auth.status === 'AUTHENTICATED' ? (
                  <button className="button button--primary" disabled={start.isPending}
                    onClick={() => start.mutate(game)}>
                    {start.isPending && start.variables.nbaGameId === game.nbaGameId ? 'Starting replay…' : 'Replay'}
                  </button>
                ) : auth.enabled ? (
                  <button className="button button--secondary" disabled={auth.signInPending}
                    onClick={() => void auth.signIn('/replays')}>Sign in to replay</button>
                ) : null}
              </div>
            </article>
          ))}
        </section>
      ) : null}
      {games.hasNextPage ? (
        <button className="button button--secondary button--full" disabled={games.isFetchingNextPage}
          onClick={() => void games.fetchNextPage()}>
          {games.isFetchingNextPage ? 'Loading…' : 'Load more games'}
        </button>
      ) : null}
      <p className="muted replay-provenance">
        Play-by-play from NBA.com as republished by the open nba_data project. Unofficial and used for personal,
        non-commercial demonstration; CourtPulse is not affiliated with the NBA.
      </p>
    </>
  );
}

function SessionRow({ session }: { session: ReplaySession }) {
  return (
    <li>
      <Link to={`/games/${encodeURIComponent(session.gameId)}`}>
        {session.awayTeamName} at {session.homeTeamName}
      </Link>
      <span className="muted">{session.status === 'PAUSED' ? 'Paused' : `${String(session.speed)}×`}</span>
      <progress max={session.totalPlays} value={session.playsReleased} aria-label="Replay progress">
        {replayProgress(session)}
      </progress>
    </li>
  );
}
