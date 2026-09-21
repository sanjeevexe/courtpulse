import { Link } from 'react-router-dom';
import type { GameSummary } from '../api/client';
import { formatClock, formatDateTime, formatPeriod, shortTeam } from '../lib/format';
import { DataStatusBadge } from './DataStatusBadge';

export function GameCard({ game }: { game: GameSummary }) {
  return (
    <article className="game-card">
      <div className="game-card__meta">
        <span>{formatPeriod(game.period, game.status)}</span>
        {game.status === 'LIVE' ? <span>{formatClock(game.clockMillisRemaining)}</span> : null}
        <DataStatusBadge status={game.dataStatus} />
      </div>
      <div className="matchup" aria-label={`${game.awayTeamId} at ${game.homeTeamId}`}>
        <div className="team-line">
          <span className="team-seed" aria-hidden="true">A</span>
          <strong>{shortTeam(game.awayTeamId)}</strong>
          <span className="score">{game.awayScore}</span>
        </div>
        <div className="team-line">
          <span className="team-seed team-seed--home" aria-hidden="true">H</span>
          <strong>{shortTeam(game.homeTeamId)}</strong>
          <span className="score">{game.homeScore}</span>
        </div>
      </div>
      <div className="game-card__footer">
        <span>Updated {formatDateTime(game.updatedAt)}</span>
        <Link className="text-link" to={`/games/${encodeURIComponent(game.gameId)}`}>
          View game <span aria-hidden="true">→</span>
        </Link>
      </div>
    </article>
  );
}
