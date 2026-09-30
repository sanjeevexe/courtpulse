import type { GameSummary } from '../api/client';
import { formatClock, formatPeriod, formatTipoff } from '../lib/format';

/** One status line for a game: a pulsing LIVE pill with period and clock, Final (with OT), or tip-off. */
export function GameStatus({ game }: {
  game: Pick<GameSummary, 'status' | 'period' | 'clockMillisRemaining' | 'scheduledAt'>;
}) {
  if (game.status === 'LIVE') {
    return (
      <span className="game-status game-status--live">
        <span className="live-pill">Live</span>
        <span className="game-status__period">{formatPeriod(game.period)}</span>
        <span className="game-status__clock">{formatClock(game.clockMillisRemaining)}</span>
      </span>
    );
  }
  if (game.status === 'FINAL') {
    return (
      <span className="game-status game-status--final">
        <span className="final-label">Final</span>
        {game.period > 4 ? <span className="game-status__period">{formatPeriod(game.period)}</span> : null}
      </span>
    );
  }
  return (
    <span className="game-status game-status--scheduled">
      <span className="upcoming-label">{game.scheduledAt ? formatTipoff(game.scheduledAt) : 'Upcoming'}</span>
    </span>
  );
}
