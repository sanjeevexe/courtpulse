export function LoadingState({ label = 'Loading games' }: { label?: string }) {
  return (
    <div className="loading-grid" role="status" aria-live="polite" aria-label={label}>
      <span className="sr-only">{label}</span>
      {[0, 1, 2].map((item) => (
        <div className="skeleton-card" key={item} aria-hidden="true">
          <span className="skeleton skeleton--short" />
          <span className="skeleton skeleton--score" />
          <span className="skeleton" />
        </div>
      ))}
    </div>
  );
}
