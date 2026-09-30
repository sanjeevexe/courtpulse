import { useLayoutEffect, useRef } from 'react';

interface Option<T> { value: T; label: string; count?: number }

/**
 * A row of toggle buttons with one sliding indicator behind the pressed one. Each option is a
 * plain button with aria-pressed, so keyboard and screen-reader use is the same as separate toggles.
 */
export function SegmentedControl<T extends string | number>({ options, value, onChange, label, disabled, size = 'md' }: {
  options: Option<T>[];
  value: T;
  onChange: (value: T) => void;
  label: string;
  disabled?: boolean;
  size?: 'sm' | 'md';
}) {
  const track = useRef<HTMLDivElement>(null);
  const thumb = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const place = () => {
      const active = track.current?.querySelector<HTMLElement>('[aria-pressed="true"]');
      const indicator = thumb.current;
      if (!indicator) return;
      if (!active) {
        indicator.style.opacity = '0';
        return;
      }
      indicator.style.opacity = '1';
      indicator.style.width = `${String(active.offsetWidth)}px`;
      indicator.style.transform = `translateX(${String(active.offsetLeft)}px)`;
    };
    place();
    // Widths change when the web font arrives or the layout reflows; follow them.
    if (typeof ResizeObserver === 'undefined' || !track.current) return undefined;
    const observer = new ResizeObserver(place);
    observer.observe(track.current);
    return () => observer.disconnect();
  }, [value, options]);

  return (
    <div className={`segmented segmented--${size}`} role="group" aria-label={label} ref={track}>
      <span className="segmented__thumb" ref={thumb} aria-hidden="true" style={{ opacity: 0 }} />
      {options.map((option) => (
        <button
          key={String(option.value)}
          type="button"
          className="segmented__option"
          aria-pressed={option.value === value}
          disabled={disabled}
          onClick={() => onChange(option.value)}
        >
          {option.label}
          {option.count !== undefined ? <span className="segmented__count">{option.count}</span> : null}
        </button>
      ))}
    </div>
  );
}
