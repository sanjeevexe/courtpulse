import { describe, expect, it } from 'vitest';
import { deliveryRefreshInterval, snapshotRefreshInterval } from './hooks';
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

describe('private delivery refresh policy', () => {
  const delivery = {
    id: '11111111-1111-1111-1111-111111111111',
    alertId: '22222222-2222-2222-2222-222222222222',
    channel: 'EMAIL' as const,
    status: 'PENDING' as const,
    attempts: 0,
    createdAt: '2026-09-22T00:00:00Z',
  };

  it('refreshes while an email is pending, leased, or retrying', () => {
    expect(deliveryRefreshInterval({ items: [delivery] })).toBe(2_000);
    expect(deliveryRefreshInterval({ items: [{ ...delivery, status: 'LEASED' }] })).toBe(2_000);
    expect(deliveryRefreshInterval({ items: [{ ...delivery, status: 'RETRY_SCHEDULED' }] })).toBe(2_000);
  });

  it('stops polling when all work is terminal', () => {
    for (const status of ['DELIVERED', 'FAILED', 'CANCELLED'] as const) {
      expect(deliveryRefreshInterval({ items: [{ ...delivery, status }] })).toBe(false);
    }
    expect(deliveryRefreshInterval({ items: [] })).toBe(false);
    expect(deliveryRefreshInterval(undefined)).toBe(false);
  });
});
