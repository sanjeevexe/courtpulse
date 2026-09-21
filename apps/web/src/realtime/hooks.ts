import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { GameSnapshot } from '../api/client';
import { snapshotQueryKey } from '../api/hooks';
import { realtimeManager, type RealtimeConnectionState } from './client';

export function useGameRealtime(gameId: string): RealtimeConnectionState {
  const queryClient = useQueryClient();
  const [state, setState] = useState<RealtimeConnectionState>('RECONNECTING');

  useEffect(() => realtimeManager.connect(gameId, {
    lastStateVersion: () => queryClient.getQueryData<GameSnapshot>(snapshotQueryKey(gameId))?.stateVersion,
    onStateUpdated: () => {
      void queryClient.invalidateQueries({ queryKey: snapshotQueryKey(gameId) });
      void queryClient.invalidateQueries({ queryKey: ['game', gameId, 'events'] });
    },
    onAlertCreated: () => {
      void queryClient.invalidateQueries({ queryKey: ['game', gameId, 'alerts'] });
    },
    onResyncRequired: () => {
      void queryClient.invalidateQueries({ queryKey: ['game', gameId] });
    },
    onStatus: setState,
  }), [gameId, queryClient]);

  return state;
}

export function connectionLabel(state: RealtimeConnectionState): string {
  if (state === 'CONNECTED') return 'Live updates connected';
  if (state === 'RECONNECTING') return 'Live updates reconnecting';
  return 'Synced by polling';
}
