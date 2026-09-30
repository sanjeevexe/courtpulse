/** A small, consistent icon set (24px grid, 2px stroke, currentColor); decorative by default. */
type IconName =
  | 'scores' | 'replay' | 'bell' | 'rules' | 'star' | 'star-filled' | 'refresh' | 'plus' | 'arrow-left'
  | 'arrow-right' | 'mail' | 'user' | 'logout' | 'close' | 'play' | 'pause' | 'skip' | 'check';

const paths: Record<IconName, string> = {
  scores: 'M4 5h16v14H4zM12 5v14M4 12h3M17 12h3',
  replay: 'M3 12a9 9 0 1 0 3-6.7M3 4v5h5M10 9l5 3-5 3z',
  bell: 'M6 16V11a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  rules: 'M4 6h10M4 12h16M4 18h7M17 4v4M13 16h7M17 14v4',
  star: 'M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z',
  'star-filled': 'M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z',
  refresh: 'M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16M20 20v-4h-4',
  plus: 'M12 5v14M5 12h14',
  'arrow-left': 'M19 12H5M11 6l-6 6 6 6',
  'arrow-right': 'M5 12h14M13 6l6 6-6 6',
  mail: 'M3 6h18v12H3zM3 7l9 6 9-6',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0',
  logout: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11',
  close: 'M6 6l12 12M18 6L6 18',
  play: 'M7 5l12 7-12 7z',
  pause: 'M7 5h3v14H7zM14 5h3v14h-3z',
  skip: 'M5 5l10 7-10 7zM18 5v14',
  check: 'M5 12l5 5 9-10',
};

export function Icon({ name, size = 18, className }: { name: IconName; size?: number; className?: string | undefined }) {
  const filled = name === 'star-filled' || name === 'play';
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false"
      fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinecap="round"
      strokeLinejoin="round">
      <path d={paths[name]} />
    </svg>
  );
}
