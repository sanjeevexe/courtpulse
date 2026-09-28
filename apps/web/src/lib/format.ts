export function formatClock(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes)}:${seconds.toString().padStart(2, '0')}`;
}

export function formatPeriod(period: number, status?: string): string {
  if (status === 'SCHEDULED' && period === 0) return 'Tipoff pending';
  if (period <= 4) return `Q${String(period)}`;
  return `OT${String(period - 4)}`;
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value));
}

export function readableEventType(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

export function shortTeam(value: string): string {
  return value.replace(/^team[_-]?/i, '').replaceAll('_', ' ').toUpperCase();
}

/** Provider display name when known; otherwise the stable ID in a readable form. */
export function teamLabel(teamId: string, name?: string | null): string {
  return name ?? shortTeam(teamId);
}

export function playerLabel(playerId: string, names?: Record<string, string>): string {
  return names?.[playerId] ?? playerId.replace(/^bdl-player-/, '#').replaceAll('_', ' ');
}
