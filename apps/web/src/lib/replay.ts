import { ApiError, getGameSnapshot, type ReplaySession } from '../api/client';

export const REPLAY_SPEEDS = [1, 10, 30, 60, 120] as const;

/** Wall-clock length of a replay at a speed, rounded for display ("about 4 min"). */
export function replayLength(durationSeconds: number, speed: number): string {
  const seconds = durationSeconds / Math.max(1, speed);
  if (seconds < 90) return `about ${String(Math.max(1, Math.round(seconds)))} s`;
  const minutes = Math.round(seconds / 60);
  return minutes < 90 ? `about ${String(minutes)} min` : `about ${(seconds / 3600).toFixed(1)} h`;
}

export function replayProgress(session: Pick<ReplaySession, 'playsReleased' | 'totalPlays'>): string {
  if (session.totalPlays === 0) return '0%';
  return `${String(Math.round((100 * session.playsReleased) / session.totalPlays))}%`;
}

/** A calendar date from the API ("2026-05-08") in the reader's locale, without time-zone drift. */
export function formatReplayDate(value: string): string {
  return new Date(`${value}T12:00:00Z`).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

/**
 * A new replay's game appears after the replay worker's next poll and a first processing pass.
 * Waits for it (up to about 30 s) so the game page opens with a scoreboard, not an empty shell.
 */
export async function waitForReplayGame(gameId: string, attempts = 30, delayMs = 1_000): Promise<void> {
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      await getGameSnapshot(gameId);
      return;
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) return;
    }
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
}
