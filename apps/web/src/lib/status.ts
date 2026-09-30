import type { DataStatus } from '../api/client';

/** Only unusual data states need a badge; live and final are already shown by the game status. */
export function isDataWarning(status: DataStatus): boolean {
  return status === 'STALE' || status === 'PROCESSING_BLOCKED';
}
