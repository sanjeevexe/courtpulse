/** A thin progress bar that eases between values; exposed to assistive tech as a progressbar. */
export function ProgressBar({ value, max, label }: { value: number; max: number; label: string }) {
  const ratio = max > 0 ? Math.min(1, Math.max(0, value / max)) : 0;
  return (
    <div className="progress" role="progressbar" aria-label={label}
      aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
      <span className="progress__fill" style={{ transform: `scaleX(${String(ratio)})` }} />
    </div>
  );
}
