import { Link } from 'react-router-dom';
import type { GameSummary } from '../api/client';
import { formatClock, formatDateTime, formatPeriod, teamLabel } from '../lib/format';
import { DataStatusBadge } from './DataStatusBadge';

export function GameCard({ game }: { game: GameSummary }) {
  return (
    <article className="game-card">
      <div className="game-card__meta">
        <span>{formatPeriod(game.period, game.status)}</span>
        {game.status === 'LIVE' ? <span>{formatClock(game.clockMillisRemaining)}</span> : null}
        <DataStatusBadge status={game.dataStatus} />
      </div>
      <div
        className="matchup"
        aria-label={`${teamLabel(game.awayTeamId, game.awayTeamName)} at ${teamLabel(game.homeTeamId, game.homeTeamName)}`}
      >
        <div className="team-line">
          <span className="team-seed" aria-hidden="true">A</span>
          <strong title={game.awayTeamName ?? undefined}>{game.awayTeamAbbreviation ?? teamLabel(game.awayTeamId)}</strong>
          <span className="score">{game.awayScore}</span>
        </div>
        <div className="team-line">
          <span className="team-seed team-seed--home" aria-hidden="true">H</span>
          <strong title={game.homeTeamName ?? undefined}>{game.homeTeamAbbreviation ?? teamLabel(game.homeTeamId)}</strong>
          <span className="score">{game.homeScore}</span>
        </div>
      </div>
      <div className="game-card__footer">
        <span>
          {game.status === 'SCHEDULED' && game.scheduledAt
            ? `Tip-off ${formatDateTime(game.scheduledAt)}`
            : `Updated ${formatDateTime(game.updatedAt)}`}
        </span>
        <Link className="text-link" to={`/games/${encodeURIComponent(game.gameId)}`}>
          View game <span aria-hidden="true">→</span>
        </Link>
      </div>
    </article>
  );
}
