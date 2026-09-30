import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import {
  controlReplay,
  setReplaySpeed,
  startReplay,
  type ReplayAction,
  type ReplaySession,
} from '../api/client';
import { useMyReplays, useReplaySessions } from '../api/hooks';
import { useAuth } from '../auth/useAuth';
import { REPLAY_SPEEDS, formatReplayDate, replayProgress, waitForReplayGame } from '../lib/replay';

/** Shown on a game that is a replay: its progress, and controls for the fan who started it. */
export function ReplayControls({ gameId }: { gameId: string }) {
  const auth = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const authenticated = auth.status === 'AUTHENTICATED';
  const sessions = useReplaySessions();
  const mine = useMyReplays(auth.accessToken, authenticated);
  const session = sessions.data?.items.find((item) => item.gameId === gameId);
  const owned = mine.data?.items.some((item) => item.gameId === gameId) ?? false;

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['replays', 'sessions'] });
    await queryClient.invalidateQueries({ queryKey: ['me', 'replays'] });
  };
  const control = useMutation({
    mutationFn: (change: { action: ReplayAction } | { speed: number }) => {
      if (!session) throw new Error('No replay to control');
      return 'speed' in change
        ? setReplaySpeed(auth.accessToken ?? '', session.sessionId, change.speed)
        : controlReplay(auth.accessToken ?? '', session.sessionId, change.action);
    },
    onSuccess: refresh,
  });
  const again = useMutation({
    mutationFn: async (current: ReplaySession) => {
      const next = await startReplay(auth.accessToken ?? '', current.nbaGameId, current.speed);
      await waitForReplayGame(next.gameId);
      return next;
    },
    onSuccess: async (next) => {
      await refresh();
      void navigate(`/games/${encodeURIComponent(next.gameId)}`);
    },
  });

  if (!session) return null;
  const busy = control.isPending || again.isPending;
  const finished = session.status === 'FINISHED';
  return (
    <section className="replay-bar" aria-label="Replay controls">
      <div className="replay-bar__summary">
        <span className="eyebrow">Replay · {formatReplayDate(session.gameDate)}</span>
        <strong>
          {finished ? 'Replay finished' : session.status === 'PAUSED' ? 'Paused' : `Replaying at ${String(session.speed)}×`}
        </strong>
        <progress max={session.totalPlays} value={session.playsReleased} aria-label="Replay progress" />
        <span className="muted">{session.playsReleased} of {session.totalPlays} plays ({replayProgress(session)})</span>
      </div>
      {authenticated ? (
        <div className="replay-bar__actions">
          {owned && !finished ? (
            <>
              <button className="button button--secondary" disabled={busy}
                onClick={() => control.mutate({ action: session.status === 'PAUSED' ? 'resume' : 'pause' })}>
                {session.status === 'PAUSED' ? 'Resume' : 'Pause'}
              </button>
              <div className="speed-group" role="group" aria-label="Replay speed">
                {REPLAY_SPEEDS.map((speed) => (
                  <button key={speed} disabled={busy} aria-pressed={session.speed === speed}
                    className={session.speed === speed ? 'filter-chip filter-chip--active' : 'filter-chip'}
                    onClick={() => control.mutate({ speed })}>{speed}×</button>
                ))}
              </div>
              <button className="button button--quiet" disabled={busy}
                onClick={() => control.mutate({ action: 'finish' })}>Skip to final</button>
            </>
          ) : null}
          <button className="button button--quiet" disabled={busy} onClick={() => again.mutate(session)}>
            {again.isPending ? 'Starting replay…' : owned ? 'Replay again' : 'Replay this game yourself'}
          </button>
        </div>
      ) : null}
      {control.isError || again.isError ? (
        <p className="form-error" role="alert">{(control.error ?? again.error)?.message}</p>
      ) : null}
    </section>
  );
}
