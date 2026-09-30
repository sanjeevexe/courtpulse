import { useState } from 'react';

/**
 * A score that ticks when it changes: the key remounts the element so its CSS animation replays,
 * and the first value never animates (opening a finished game should not flash every number).
 */
export function Score({ value, className = '' }: { value: number; className?: string }) {
  const [initial] = useState(value);
  const changed = value !== initial;
  return <strong key={value} className={`${className}${changed ? ' is-changed' : ''}`}>{value}</strong>;
}
