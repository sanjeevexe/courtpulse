import type { CSSProperties } from 'react';
import { teamCode, teamColor } from '../lib/teams';

type Size = 'sm' | 'md' | 'lg';

/**
 * A team monogram on the team's color (neutral for a team that is not an NBA franchise).
 * Decorative (the name is always shown beside it), so the code is drawn by CSS from data-code
 * rather than repeated as page text.
 */
export function TeamBadge({ name, abbreviation, size = 'md' }: {
  name?: string | null | undefined;
  abbreviation?: string | null | undefined;
  size?: Size;
}) {
  const code = teamCode(name, abbreviation);
  const color = teamColor(code);
  const style = color ? ({ '--team': color } as CSSProperties) : undefined;
  return (
    <span className={`team-badge team-badge--${size}${color ? ' team-badge--colored' : ''}`} style={style}
      data-code={code} aria-hidden="true" />
  );
}
