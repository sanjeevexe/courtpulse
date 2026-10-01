export type Conference = 'East' | 'West';

/** NBA franchises: code, full name, primary color (team badges), and conference (playoff titles). */
const NBA_TEAMS: { code: string; name: string; color: string; conference: Conference }[] = [
  { code: 'ATL', name: 'Atlanta Hawks', color: '#C8102E', conference: 'East' },
  { code: 'BOS', name: 'Boston Celtics', color: '#007A33', conference: 'East' },
  { code: 'BKN', name: 'Brooklyn Nets', color: '#3A3A3A', conference: 'East' },
  { code: 'CHA', name: 'Charlotte Hornets', color: '#1D8CAB', conference: 'East' },
  { code: 'CHI', name: 'Chicago Bulls', color: '#CE1141', conference: 'East' },
  { code: 'CLE', name: 'Cleveland Cavaliers', color: '#860038', conference: 'East' },
  { code: 'DAL', name: 'Dallas Mavericks', color: '#0064B1', conference: 'West' },
  { code: 'DEN', name: 'Denver Nuggets', color: '#0E2240', conference: 'West' },
  { code: 'DET', name: 'Detroit Pistons', color: '#C8102E', conference: 'East' },
  { code: 'GSW', name: 'Golden State Warriors', color: '#1D428A', conference: 'West' },
  { code: 'HOU', name: 'Houston Rockets', color: '#CE1141', conference: 'West' },
  { code: 'IND', name: 'Indiana Pacers', color: '#002D62', conference: 'East' },
  { code: 'LAC', name: 'LA Clippers', color: '#C8102E', conference: 'West' },
  { code: 'LAL', name: 'Los Angeles Lakers', color: '#552583', conference: 'West' },
  { code: 'MEM', name: 'Memphis Grizzlies', color: '#5D76A9', conference: 'West' },
  { code: 'MIA', name: 'Miami Heat', color: '#98002E', conference: 'East' },
  { code: 'MIL', name: 'Milwaukee Bucks', color: '#00471B', conference: 'East' },
  { code: 'MIN', name: 'Minnesota Timberwolves', color: '#236192', conference: 'West' },
  { code: 'NOP', name: 'New Orleans Pelicans', color: '#0C2340', conference: 'West' },
  { code: 'NYK', name: 'New York Knicks', color: '#006BB6', conference: 'East' },
  { code: 'OKC', name: 'Oklahoma City Thunder', color: '#007AC1', conference: 'West' },
  { code: 'ORL', name: 'Orlando Magic', color: '#0077C0', conference: 'East' },
  { code: 'PHI', name: 'Philadelphia 76ers', color: '#006BB6', conference: 'East' },
  { code: 'PHX', name: 'Phoenix Suns', color: '#E56020', conference: 'West' },
  { code: 'POR', name: 'Portland Trail Blazers', color: '#E03A3E', conference: 'West' },
  { code: 'SAC', name: 'Sacramento Kings', color: '#5A2D81', conference: 'West' },
  { code: 'SAS', name: 'San Antonio Spurs', color: '#6B7A85', conference: 'West' },
  { code: 'TOR', name: 'Toronto Raptors', color: '#CE1141', conference: 'East' },
  { code: 'UTA', name: 'Utah Jazz', color: '#3E2680', conference: 'West' },
  { code: 'WAS', name: 'Washington Wizards', color: '#002B5C', conference: 'East' },
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

/** The franchise's primary color, or null for a team that is not an NBA franchise. */
export function teamColor(code: string): string | null {
  return byCode.get(code)?.color ?? null;
}

/** The team's conference, from its code or full name; null when unknown. */
export function teamConference(nameOrCode?: string | null): Conference | null {
  if (!nameOrCode) return null;
  return (byCode.get(nameOrCode.toUpperCase()) ?? byName.get(nameOrCode.toLowerCase()))?.conference ?? null;
}
