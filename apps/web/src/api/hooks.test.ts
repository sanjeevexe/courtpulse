import { describe, expect, it } from 'vitest';
import { snapshotRefreshInterval } from './hooks';
import { snapshot } from '../test/fixtures';

describe('snapshot polling policy', () => {
  it('polls fresh live games on a bounded interval', () => {
    expect(snapshotRefreshInterval({ ...snapshot, status: 'LIVE', dataStatus: 'LIVE' })).toBe(15_000);
  });

  it('stops automatic polling for final games', () => {
    expect(snapshotRefreshInterval(snapshot)).toBe(false);
  });

  it('backs off for stale or processing-blocked games', () => {
    expect(snapshotRefreshInterval({ ...snapshot, status: 'LIVE', dataStatus: 'STALE' })).toBe(30_000);
    expect(snapshotRefreshInterval({ ...snapshot, status: 'LIVE', dataStatus: 'PROCESSING_BLOCKED' }))
      .toBe(30_000);
  });

  it('does not poll before the first snapshot arrives', () => {
    expect(snapshotRefreshInterval(undefined)).toBe(false);
  });

  it('suppresses redundant polling while realtime is connected', () => {
    expect(snapshotRefreshInterval({ ...snapshot, status: 'LIVE', dataStatus: 'LIVE' }, true))
      .toBe(false);
  });
});
