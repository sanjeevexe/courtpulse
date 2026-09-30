/** NBA franchises: code, full name, and primary color (used for team badges). */
const NBA_TEAMS: { code: string; name: string; color: string }[] = [
  { code: 'ATL', name: 'Atlanta Hawks', color: '#C8102E' },
  { code: 'BOS', name: 'Boston Celtics', color: '#007A33' },
  { code: 'BKN', name: 'Brooklyn Nets', color: '#3A3A3A' },
  { code: 'CHA', name: 'Charlotte Hornets', color: '#1D8CAB' },
  { code: 'CHI', name: 'Chicago Bulls', color: '#CE1141' },
  { code: 'CLE', name: 'Cleveland Cavaliers', color: '#860038' },
  { code: 'DAL', name: 'Dallas Mavericks', color: '#0064B1' },
  { code: 'DEN', name: 'Denver Nuggets', color: '#0E2240' },
  { code: 'DET', name: 'Detroit Pistons', color: '#C8102E' },
  { code: 'GSW', name: 'Golden State Warriors', color: '#1D428A' },
  { code: 'HOU', name: 'Houston Rockets', color: '#CE1141' },
  { code: 'IND', name: 'Indiana Pacers', color: '#002D62' },
  { code: 'LAC', name: 'LA Clippers', color: '#C8102E' },
  { code: 'LAL', name: 'Los Angeles Lakers', color: '#552583' },
  { code: 'MEM', name: 'Memphis Grizzlies', color: '#5D76A9' },
  { code: 'MIA', name: 'Miami Heat', color: '#98002E' },
  { code: 'MIL', name: 'Milwaukee Bucks', color: '#00471B' },
  { code: 'MIN', name: 'Minnesota Timberwolves', color: '#236192' },
  { code: 'NOP', name: 'New Orleans Pelicans', color: '#0C2340' },
  { code: 'NYK', name: 'New York Knicks', color: '#006BB6' },
  { code: 'OKC', name: 'Oklahoma City Thunder', color: '#007AC1' },
  { code: 'ORL', name: 'Orlando Magic', color: '#0077C0' },
  { code: 'PHI', name: 'Philadelphia 76ers', color: '#006BB6' },
  { code: 'PHX', name: 'Phoenix Suns', color: '#E56020' },
  { code: 'POR', name: 'Portland Trail Blazers', color: '#E03A3E' },
  { code: 'SAC', name: 'Sacramento Kings', color: '#5A2D81' },
  { code: 'SAS', name: 'San Antonio Spurs', color: '#6B7A85' },
  { code: 'TOR', name: 'Toronto Raptors', color: '#CE1141' },
  { code: 'UTA', name: 'Utah Jazz', color: '#3E2680' },
  { code: 'WAS', name: 'Washington Wizards', color: '#002B5C' },
];

export const NBA_TEAM_CODES = NBA_TEAMS.map((team) => team.code);

const byCode = new Map(NBA_TEAMS.map((team) => [team.code, team]));
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

/** The team's color: the franchise color for NBA teams, otherwise a stable hue from the code. */
export function teamColor(code: string): string {
  const known = byCode.get(code);
  if (known) return known.color;
  let hash = 0;
  for (const character of code) hash = (hash * 31 + character.charCodeAt(0)) % 360;
  return `hsl(${String(hash)} 55% 38%)`;
}
