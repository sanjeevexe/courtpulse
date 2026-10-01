import { teamConference } from './teams';

const ROUNDS: Record<string, string> = {
  '1': 'First Round',
  '2': 'Semifinals',
  '3': 'Finals',
};

/** The NBA game ID inside a replay's game ID ("nba-replay-0042500311-1" → "0042500311"). */
export function nbaGameIdOf(gameId: string): string | null {
  return /^nba-replay-(\d{10})-\d+$/.exec(gameId)?.[1] ?? /^(\d{10})$/.exec(gameId)?.[1] ?? null;
}

/**
 * The playoff title of an NBA game, from its ID (004YY00RSG: round R, series S, game G) and a
 * team's code or name for the conference: "Western Conference Finals · Game 1",
 * "NBA Finals · Game 5". Null for any other game.
 */
export function playoffTitle(gameId: string, team?: string | null): string | null {
  const nbaGameId = nbaGameIdOf(gameId);
  if (!nbaGameId) return null;
  if (nbaGameId.startsWith('005')) return 'Play-In Tournament';
  const match = /^004\d{2}00([1-4])\d([1-7])$/.exec(nbaGameId);
  if (!match) return null;
  const [, round = '', game = ''] = match;
  if (round === '4') return `NBA Finals · Game ${game}`;
  const conference = teamConference(team);
  const stage = conference ? `${conference}ern Conference ${ROUNDS[round] ?? ''}` : `Conference ${ROUNDS[round] ?? ''}`;
  return `${round === '1' && !conference ? 'First Round' : stage} · Game ${game}`;
}

/** The playoff title for a game shown by label ("Away at Home"), using the home team's name. */
export function playoffTitleForLabel(gameId: string, label?: string | null): string | null {
  return playoffTitle(gameId, label?.split(' at ').pop()?.trim() ?? null);
}
