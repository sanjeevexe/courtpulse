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
  listMyRules,
  listMyAlerts,
  getNotificationSettings,
  listDeliveryHistory,
  listDeliveryAttempts,
  type RulePage,
  type OwnedAlertPage,
  type DeliveryHistoryPage,
  listMyReplays,
  listReplayGames,
  listReplaySessions,
  type ReplayGamePage,
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

export interface RuleTarget {
  id: string;
  label: string;
}

/** Teams and scoring players of one game, offered as suggestions when creating a rule for it. */
export function useRuleTargets(gameId: string | null) {
  return useQuery({
    queryKey: ['game', gameId, 'rule-targets'],
    enabled: gameId !== null && gameId !== '',
    staleTime: 30_000,
    queryFn: async () => {
      const { snapshot } = await getGameSnapshot(gameId ?? '');
      if (!snapshot) return { teams: [] as RuleTarget[], players: [] as RuleTarget[] };
      const teams: RuleTarget[] = [
        { id: snapshot.homeTeamId, label: snapshot.homeTeamName ?? snapshot.homeTeamId },
        { id: snapshot.awayTeamId, label: snapshot.awayTeamName ?? snapshot.awayTeamId },
      ];
      const players: RuleTarget[] = Object.keys(snapshot.playerPoints)
        .map((id) => ({ id, label: snapshot.playerNames[id] ?? id }))
        .sort((left, right) => left.label.localeCompare(right.label));
      return { teams, players };
    },
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

export function useMyRules(accessToken: string | null, authenticated: boolean) {
  return useInfiniteQuery({
    queryKey: ['me', 'rules'],
    queryFn: ({ pageParam }) => listMyRules(accessToken ?? '', pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page: RulePage) => page.nextCursor ?? undefined,
    enabled: authenticated && accessToken !== null,
  });
}

export function useMyAlerts(accessToken: string | null, authenticated: boolean, refetchInterval: number | false = false) {
  return useInfiniteQuery({
    queryKey: ['me', 'alerts'],
    queryFn: ({ pageParam }) => listMyAlerts(accessToken ?? '', pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page: OwnedAlertPage) => page.nextCursor ?? undefined,
    enabled: authenticated && accessToken !== null,
    refetchInterval,
    refetchIntervalInBackground: false,
  });
}

export function useNotificationSettings(accessToken: string | null, authenticated: boolean) {
  return useQuery({
    queryKey: ['me', 'notification-settings'],
    queryFn: () => getNotificationSettings(accessToken ?? ''),
    enabled: authenticated && accessToken !== null,
  });
}

export function useDeliveryHistory(accessToken: string | null, authenticated: boolean) {
  return useQuery({
    queryKey: ['me', 'delivery-history'],
    queryFn: () => listDeliveryHistory(accessToken ?? ''),
    enabled: authenticated && accessToken !== null,
    refetchInterval: (query) => deliveryRefreshInterval(query.state.data),
  });
}

export function useDeliveryAttempts(
  accessToken: string | null, authenticated: boolean, deliveryId: string, expanded: boolean,
) {
  return useQuery({
    queryKey: ['me', 'delivery-attempts', deliveryId],
    queryFn: () => listDeliveryAttempts(accessToken ?? '', deliveryId),
    enabled: authenticated && accessToken !== null && expanded,
  });
}

export function deliveryRefreshInterval(history: DeliveryHistoryPage | undefined): number | false {
  if (!history) return false;
  return history.items.some((delivery) =>
    delivery.status === 'PENDING' || delivery.status === 'LEASED'
      || delivery.status === 'RETRY_SCHEDULED') ? 2_000 : false;
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

export function useReplayGames(team: string | null) {
  return useInfiniteQuery({
    queryKey: ['replays', 'games', team],
    queryFn: ({ pageParam }) => listReplayGames(team, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page: ReplayGamePage) => page.nextAfter ?? undefined,
  });
}

/** Public replay list; polled while any replay is running so progress stays current. */
export function useReplaySessions() {
  return useQuery({
    queryKey: ['replays', 'sessions'],
    queryFn: listReplaySessions,
    refetchInterval: (query) =>
      query.state.data?.items.some((session) => session.status !== 'FINISHED') ? 5_000 : 30_000,
    refetchIntervalInBackground: false,
  });
}

export function useMyReplays(accessToken: string | null, authenticated: boolean) {
  return useQuery({
    queryKey: ['me', 'replays'],
    queryFn: () => listMyReplays(accessToken ?? ''),
    enabled: authenticated && accessToken !== null,
  });
}
