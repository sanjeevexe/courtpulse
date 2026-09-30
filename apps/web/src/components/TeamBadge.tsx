import type { CSSProperties } from 'react';
import { teamCode, teamColor } from '../lib/teams';

type Size = 'sm' | 'md' | 'lg';

/**
 * A team monogram on the team's color. Decorative (the name is always shown beside it), so the code
 * is drawn by CSS from data-code rather than repeated as page text.
 */
export function TeamBadge({ name, abbreviation, size = 'md' }: {
  name?: string | null | undefined;
  abbreviation?: string | null | undefined;
  size?: Size;
}) {
  const code = teamCode(name, abbreviation);
  const style = { '--team': teamColor(code) } as CSSProperties;
  return <span className={`team-badge team-badge--${size}`} style={style} data-code={code} aria-hidden="true" />;
}
