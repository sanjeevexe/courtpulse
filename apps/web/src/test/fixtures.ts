import type {
  AlertPage,
  EventPage,
  GameAlert,
  GameEvent,
  GamePage,
  GameSnapshot,
  GameSummary,
} from '../api/client';

export const game: GameSummary = {
  gameId: 'game_synthetic_001',
  source: 'synthetic',
  homeTeamId: 'team_home',
  awayTeamId: 'team_away',
  status: 'FINAL',
  stateVersion: 20,
  homeScore: 18,
  awayScore: 14,
  period: 4,
  clockMillisRemaining: 0,
  lastAppliedSequence: 20,
  updatedAt: '2026-09-20T16:00:00Z',
  stateChecksum: '06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca',
  dataStatus: 'FINAL',
};

export const events: GameEvent[] = Array.from({ length: 20 }, (_, index) => {
  const sequence = index + 1;
  return {
    eventId: `event-${String(sequence)}`,
    schemaVersion: 1,
    gameId: game.gameId,
    source: 'synthetic',
    providerEventId: `provider-${String(sequence)}`,
    sequence,
    revision: 1,
    eventType: sequence === 20 ? 'GAME_ENDED' : 'SHOT_MADE',
    period: Math.min(4, Math.ceil(sequence / 5)),
    clockMillisRemaining: Math.max(0, 720_000 - sequence * 30_000),
    occurredAt: `2026-09-20T15:${String(sequence).padStart(2, '0')}:00Z`,
    teamId: sequence % 2 === 0 ? 'team_home' : 'team_away',
    participantIds: sequence === 10 ? ['player_ace'] : [],
    scoreAfter: { home: Math.min(18, sequence), away: Math.min(14, sequence - 1) },
    points: sequence === 20 ? 0 : 2,
  };
});

export const snapshot: GameSnapshot = {
  gameId: game.gameId,
  source: game.source,
  homeTeamId: game.homeTeamId,
  awayTeamId: game.awayTeamId,
  status: game.status,
  period: game.period,
  clockMillisRemaining: game.clockMillisRemaining,
  homeScore: game.homeScore,
  awayScore: game.awayScore,
  playerPoints: { player_ace: 13, player_rim: 5 },
  stateVersion: game.stateVersion,
  lastAppliedSequence: game.lastAppliedSequence,
  stateChecksum: game.stateChecksum ?? null,
  recentEvents: events.slice(10).map((event) => ({
    eventId: event.eventId,
    sequence: event.sequence,
    revision: event.revision,
    eventType: event.eventType,
    occurredAt: event.occurredAt,
    scoreAfter: event.scoreAfter,
  })),
  updatedAt: game.updatedAt,
  dataStatus: game.dataStatus,
  playerNames: {},
};

/** A live provider game in overtime with display names and play text. */
export const providerSnapshot: GameSnapshot = {
  ...snapshot,
  gameId: 'bdl-game-990001',
  source: 'balldontlie',
  homeTeamId: 'bdl-team-90001',
  awayTeamId: 'bdl-team-90002',
  status: 'LIVE',
  period: 5,
  clockMillisRemaining: 125_000,
  homeScore: 77,
  awayScore: 75,
  playerPoints: { 'bdl-player-9000101': 30, 'bdl-player-9000204': 18 },
  playerNames: { 'bdl-player-9000101': 'Ada Lane' },
  homeTeamName: 'Harbor City Herons',
  homeTeamAbbreviation: 'HCH',
  awayTeamName: 'Summit Valley Sentinels',
  awayTeamAbbreviation: 'SVS',
  dataStatus: 'LIVE',
  recentEvents: [{
    eventId: 'bdl-990001-160-r1',
    sequence: 160,
    revision: 1,
    eventType: 'FIELD_GOAL_MADE',
    occurredAt: '2026-09-20T02:30:00Z',
    scoreAfter: { home: 77, away: 75 },
    description: 'Ada Lane makes 25-foot three point jumper',
  }],
};

export const alert: GameAlert = {
  ruleId: 'milestone-player-ace-10',
  triggerKey: 'player_ace:10',
  gameId: game.gameId,
  triggeringEventId: 'event-10',
  title: 'player_ace reached 10 points',
  context: { playerId: 'player_ace', points: '10' },
  status: 'CREATED',
  createdAt: '2026-09-20T15:10:00Z',
};

export const gamePage = (items: GameSummary[] = [game], nextCursor: string | null = null): GamePage => ({
  items,
  nextCursor,
});

export const eventPage = (items: GameEvent[], nextCursor: string | null = null): EventPage => ({
  items,
  nextCursor,
});

export const alertPage = (items: GameAlert[] = [alert], nextCursor: string | null = null): AlertPage => ({
  items,
  nextCursor,
});
