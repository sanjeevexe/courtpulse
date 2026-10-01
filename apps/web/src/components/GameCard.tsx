import { Link } from 'react-router-dom';
import type { GameSummary } from '../api/client';
import { formatDateTime, teamLabel } from '../lib/format';
import { playoffTitle } from '../lib/playoffs';
import { isDataWarning } from '../lib/status';
import { DataStatusBadge } from './DataStatusBadge';
import { GameStatus } from './GameStatus';
import { Score } from './Score';
import { TeamBadge } from './TeamBadge';

export function GameCard({ game }: { game: GameSummary }) {
  const awayLabel = teamLabel(game.awayTeamId, game.awayTeamName);
  const homeLabel = teamLabel(game.homeTeamId, game.homeTeamName);
  const final = game.status === 'FINAL';
  const awayWon = final && game.awayScore > game.homeScore;
  const homeWon = final && game.homeScore > game.awayScore;
  const title = playoffTitle(game.gameId, game.homeTeamAbbreviation ?? game.homeTeamName);
  return (
    <article className={`game-card game-card--${game.status.toLowerCase()}`}>
      <div className="game-card__meta">
        <GameStatus game={game} />
        {title ? <span className="game-card__title">{title}</span> : null}
        {isDataWarning(game.dataStatus) ? <DataStatusBadge status={game.dataStatus} /> : null}
      </div>
      <div className="matchup" aria-label={`${awayLabel} at ${homeLabel}`}>
        <TeamLine name={game.awayTeamName} abbreviation={game.awayTeamAbbreviation} label={awayLabel}
          score={game.awayScore} dim={homeWon} scheduled={game.status === 'SCHEDULED'} />
        <TeamLine name={game.homeTeamName} abbreviation={game.homeTeamAbbreviation} label={homeLabel}
          score={game.homeScore} dim={awayWon} scheduled={game.status === 'SCHEDULED'} />
      </div>
      <div className="game-card__footer">
        <span>
          {game.status === 'SCHEDULED' && game.scheduledAt
            ? `Tip-off ${formatDateTime(game.scheduledAt)}`
            : `Updated ${formatDateTime(game.updatedAt)}`}
        </span>
        <Link className="game-card__link" to={`/games/${encodeURIComponent(game.gameId)}`}>
          View game <span aria-hidden="true">→</span>
        </Link>
      </div>
    </article>
  );
}

function TeamLine({ name, abbreviation, label, score, dim, scheduled }: {
  name?: string | null | undefined;
  abbreviation?: string | null | undefined;
  label: string;
  score: number;
  dim: boolean;
  scheduled: boolean;
}) {
  return (
    <div className={`team-line${dim ? ' team-line--dim' : ''}`}>
      <TeamBadge name={label} abbreviation={abbreviation} />
      <span className="team-line__names">
        <strong title={name ?? undefined}>{abbreviation ?? label}</strong>
        {name && abbreviation ? <small>{name}</small> : null}
      </span>
      {scheduled ? <span className="score score--pending">–</span> : <Score className="score" value={score} />}
    </div>
  );
}
