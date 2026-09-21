import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query';
import {
  getGameSnapshot,
  listAlerts,
  listEvents,
  listGames,
  ApiError,
  type AlertPage,
  type EventPage,
  type GameAlert,
  type GameEvent,
  type GamePage,
  type GameSnapshot,
  type GameSummary,
} from './client';

const snapshotEtags = new Map<string, string>();

export const snapshotQueryKey = (gameId: string) => ['game', gameId, 'snapshot'] as const;

export function useGames(status: GameSummary['status'] | 'ALL') {
  return useInfiniteQuery({
    queryKey: ['games', status],
    queryFn: ({ pageParam }) => listGames(status, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page: GamePage) => page.nextCursor ?? undefined,
  });
}

export function useGameSnapshot(gameId: string, realtimeConnected = false) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: snapshotQueryKey(gameId),
    queryFn: async () => {
      const previous = queryClient.getQueryData<GameSnapshot>(snapshotQueryKey(gameId));
      const response = await getGameSnapshot(gameId, snapshotEtags.get(gameId));
      if (response.etag) snapshotEtags.set(gameId, response.etag);
      if (response.unchanged) {
        if (!previous) {
          throw new ApiError('The server returned 304 before a snapshot was cached.', {
            kind: 'malformed',
          });
        }
        return previous;
      }
      if (!response.snapshot) {
        throw new ApiError('The server returned an empty snapshot.', { kind: 'malformed' });
      }
      return response.snapshot;
    },
    refetchInterval: (query) => snapshotRefreshInterval(query.state.data, realtimeConnected),
    refetchIntervalInBackground: false,
  });
}

export function snapshotRefreshInterval(
  snapshot: GameSnapshot | undefined,
  realtimeConnected = false,
): number | false {
  if (!snapshot) return false;
  if (snapshot.status === 'FINAL') return false;
  if (realtimeConnected) return false;
  if (snapshot.status === 'LIVE' && snapshot.dataStatus === 'LIVE') return 15_000;
  if (snapshot.dataStatus === 'STALE' || snapshot.dataStatus === 'PROCESSING_BLOCKED') return 30_000;
  return 60_000;
}

export function useEvents(gameId: string) {
  return useInfiniteQuery({
    queryKey: ['game', gameId, 'events'],
    queryFn: ({ pageParam }) => listEvents(gameId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page: EventPage) => page.nextCursor ?? undefined,
  });
}

export function useAlerts(gameId: string) {
  return useInfiniteQuery({
    queryKey: ['game', gameId, 'alerts'],
    queryFn: ({ pageParam }) => listAlerts(gameId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page: AlertPage) => page.nextCursor ?? undefined,
  });
}

export function uniqueGames(data: InfiniteData<GamePage> | undefined): GameSummary[] {
  return uniqueBy(data?.pages.flatMap((page) => page.items) ?? [], (game) => game.gameId);
}

export function uniqueEvents(data: InfiniteData<EventPage> | undefined): GameEvent[] {
  return uniqueBy(data?.pages.flatMap((page) => page.items) ?? [], (event) => event.eventId);
}

export function uniqueAlerts(data: InfiniteData<AlertPage> | undefined): GameAlert[] {
  return uniqueBy(data?.pages.flatMap((page) => page.items) ?? [], (alert) => alert.triggerKey);
}

function uniqueBy<T>(items: T[], identity: (item: T) => string): T[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    const key = identity(item);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
