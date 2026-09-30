/** Skeletons shaped like what is loading: a grid of game cards, a scoreboard, or a list. */
export function LoadingState({ label = 'Loading games', variant = 'cards' }: {
  label?: string;
  variant?: 'cards' | 'detail' | 'list';
}) {
  return (
    <div className={`loading loading--${variant}`} role="status" aria-live="polite" aria-label={label}>
      <span className="sr-only">{label}</span>
      {variant === 'detail' ? (
        <>
          <div className="skeleton-block skeleton-block--hero" aria-hidden="true">
            <span className="skeleton skeleton--short" />
            <span className="skeleton skeleton--score" />
          </div>
          <div className="skeleton-block" aria-hidden="true">
            <span className="skeleton skeleton--short" />
            <span className="skeleton" />
            <span className="skeleton" />
          </div>
        </>
      ) : variant === 'list' ? (
        [0, 1, 2].map((item) => (
          <div className="skeleton-row" key={item} aria-hidden="true">
            <span className="skeleton skeleton--dot" />
            <span className="skeleton" />
          </div>
        ))
      ) : (
        [0, 1, 2].map((item) => (
          <div className="skeleton-block" key={item} aria-hidden="true">
            <span className="skeleton skeleton--short" />
            <span className="skeleton skeleton--line" />
            <span className="skeleton skeleton--line" />
          </div>
        ))
      )}
    </div>
  );
}
