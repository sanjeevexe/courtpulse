/** NBA franchises: code and full name (used for team badges and the replay team filter). */
const NBA_TEAMS: { code: string; name: string }[] = [
  { code: 'ATL', name: 'Atlanta Hawks' },
  { code: 'BOS', name: 'Boston Celtics' },
  { code: 'BKN', name: 'Brooklyn Nets' },
  { code: 'CHA', name: 'Charlotte Hornets' },
  { code: 'CHI', name: 'Chicago Bulls' },
  { code: 'CLE', name: 'Cleveland Cavaliers' },
  { code: 'DAL', name: 'Dallas Mavericks' },
  { code: 'DEN', name: 'Denver Nuggets' },
  { code: 'DET', name: 'Detroit Pistons' },
  { code: 'GSW', name: 'Golden State Warriors' },
  { code: 'HOU', name: 'Houston Rockets' },
  { code: 'IND', name: 'Indiana Pacers' },
  { code: 'LAC', name: 'LA Clippers' },
  { code: 'LAL', name: 'Los Angeles Lakers' },
  { code: 'MEM', name: 'Memphis Grizzlies' },
  { code: 'MIA', name: 'Miami Heat' },
  { code: 'MIL', name: 'Milwaukee Bucks' },
  { code: 'MIN', name: 'Minnesota Timberwolves' },
  { code: 'NOP', name: 'New Orleans Pelicans' },
  { code: 'NYK', name: 'New York Knicks' },
  { code: 'OKC', name: 'Oklahoma City Thunder' },
  { code: 'ORL', name: 'Orlando Magic' },
  { code: 'PHI', name: 'Philadelphia 76ers' },
  { code: 'PHX', name: 'Phoenix Suns' },
  { code: 'POR', name: 'Portland Trail Blazers' },
  { code: 'SAC', name: 'Sacramento Kings' },
  { code: 'SAS', name: 'San Antonio Spurs' },
  { code: 'TOR', name: 'Toronto Raptors' },
  { code: 'UTA', name: 'Utah Jazz' },
  { code: 'WAS', name: 'Washington Wizards' },
];

export const NBA_TEAM_CODES = NBA_TEAMS.map((team) => team.code);

const byName = new Map(NBA_TEAMS.map((team) => [team.name.toLowerCase(), team]));

/** A short team code: the provider's abbreviation, a known NBA name, or initials of the name. */
export function teamCode(name?: string | null, abbreviation?: string | null): string {
  if (abbreviation) return abbreviation.toUpperCase();
  if (!name) return '—';
  const known = byName.get(name.toLowerCase());
  if (known) return known.code;
  const words = name.replace(/^team[_-]?/i, '').split(/[\s_-]+/).filter(Boolean);
  return (words.length > 1 ? words.map((word) => word.charAt(0)).join('') : words[0] ?? '')
    .slice(0, 3).toUpperCase();
}
