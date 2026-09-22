import type { components } from './generated/schema';

export type GamePage = components['schemas']['GamePage'];
export type GameSummary = components['schemas']['GameSummary'];
export type GameSnapshot = components['schemas']['GameSnapshot'];
export type EventPage = components['schemas']['EventPage'];
export type GameEvent = components['schemas']['Event'];
export type AlertPage = components['schemas']['AlertPage'];
export type GameAlert = components['schemas']['Alert'];
export type AuthenticatedUser = components['schemas']['Me'];
export type FollowedGamePage = components['schemas']['FollowedGamePage'];
export type FollowedGame = components['schemas']['FollowedGame'];
export type DataStatus = components['schemas']['DataStatus'];
export type ProblemDetails = components['schemas']['Problem'];
export type AlertRule = components['schemas']['AlertRule'];
export type CreateAlertRule = components['schemas']['CreateAlertRule'];
export type RulePage = components['schemas']['RulePage'];
export type UpdateAlertRule = components['schemas']['UpdateAlertRule'];
export type OwnedAlert = components['schemas']['OwnedAlert'];
export type OwnedAlertPage = components['schemas']['OwnedAlertPage'];

type FailureKind = 'problem' | 'network' | 'timeout' | 'malformed';

export class ApiError extends Error {
  readonly kind: FailureKind;
  readonly status: number | null;
  readonly code: string;
  readonly correlationId: string | null;

  constructor(
    message: string,
    options: {
      kind: FailureKind;
      status?: number | null;
      code?: string;
      correlationId?: string | null;
      cause?: unknown;
    },
  ) {
    super(message, { cause: options.cause });
    this.name = 'ApiError';
    this.kind = options.kind;
    this.status = options.status ?? null;
    this.code = options.code ?? options.kind;
    this.correlationId = options.correlationId ?? null;
  }
}

export interface SnapshotResponse {
  snapshot: GameSnapshot | null;
  etag: string | null;
  unchanged: boolean;
}

const REQUEST_TIMEOUT_MS = 10_000;

async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort('CourtPulse request timed out'), REQUEST_TIMEOUT_MS);
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json, application/problem+json');
  try {
    return await fetch(new URL(path, window.location.origin), {
      ...init,
      signal: controller.signal,
      headers,
    });
  } catch (error) {
    if (controller.signal.aborted) {
      throw new ApiError('The request timed out. Try again.', { kind: 'timeout', cause: error });
    }
    throw new ApiError('CourtPulse could not reach the server.', { kind: 'network', cause: error });
  } finally {
    window.clearTimeout(timeout);
  }
}

function bearer(accessToken: string, method = 'GET'): RequestInit {
  return { method, headers: { Authorization: `Bearer ${accessToken}` } };
}

async function parseJson<T>(response: Response): Promise<T> {
  try {
    const value: unknown = await response.json();
    if (typeof value !== 'object' || value === null) {
      throw new Error('Expected a JSON object');
    }
    return value as T;
  } catch (error) {
    throw new ApiError('CourtPulse received an unexpected server response.', {
      kind: 'malformed',
      status: response.status,
      correlationId: response.headers.get('X-Correlation-ID'),
      cause: error,
    });
  }
}

async function expectOk<T>(response: Response): Promise<T> {
  if (response.ok) {
    return parseJson<T>(response);
  }
  const contentType = response.headers.get('content-type') ?? '';
  if (contentType.includes('application/problem+json')) {
    const problem = await parseJson<ProblemDetails>(response);
    throw new ApiError(problem.detail, {
      kind: 'problem',
      status: problem.status,
      code: problem.code,
      correlationId: problem.correlationId,
    });
  }
  throw new ApiError('CourtPulse received an unexpected server response.', {
    kind: 'malformed',
    status: response.status,
    correlationId: response.headers.get('X-Correlation-ID'),
  });
}

export async function listGames(
  status: GameSummary['status'] | 'ALL',
  cursor: string | null,
  limit = 8,
): Promise<GamePage> {
  const search = new URLSearchParams({ limit: String(limit) });
  if (status !== 'ALL') search.set('status', status);
  if (cursor) search.set('cursor', cursor);
  return expectOk<GamePage>(await apiFetch(`/api/v1/games?${search.toString()}`));
}

export async function getGameSnapshot(gameId: string, etag?: string): Promise<SnapshotResponse> {
  const response = await apiFetch(`/api/v1/games/${encodeURIComponent(gameId)}`, {
    headers: etag ? { 'If-None-Match': etag } : {},
  });
  if (response.status === 304) {
    return { snapshot: null, etag: response.headers.get('ETag') ?? etag ?? null, unchanged: true };
  }
  const snapshot = await expectOk<GameSnapshot>(response);
  return { snapshot, etag: response.headers.get('ETag'), unchanged: false };
}

export async function listEvents(
  gameId: string,
  cursor: string | null,
  limit = 8,
): Promise<EventPage> {
  const search = new URLSearchParams({ limit: String(limit) });
  if (cursor) search.set('cursor', cursor);
  return expectOk<EventPage>(
    await apiFetch(`/api/v1/games/${encodeURIComponent(gameId)}/events?${search.toString()}`),
  );
}

export async function listAlerts(gameId: string, cursor: string | null, limit = 8): Promise<AlertPage> {
  const search = new URLSearchParams({ limit: String(limit) });
  if (cursor) search.set('cursor', cursor);
  return expectOk<AlertPage>(
    await apiFetch(`/api/v1/games/${encodeURIComponent(gameId)}/alerts?${search.toString()}`),
  );
}

export async function getAuthenticatedUser(accessToken: string): Promise<AuthenticatedUser> {
  return expectOk<AuthenticatedUser>(await apiFetch('/api/v1/me', bearer(accessToken)));
}

export async function listFollowedGames(accessToken: string): Promise<FollowedGamePage> {
  return expectOk<FollowedGamePage>(await apiFetch('/api/v1/me/followed-games', bearer(accessToken)));
}

export async function followGame(accessToken: string, gameId: string): Promise<FollowedGame> {
  return expectOk<FollowedGame>(await apiFetch(
    `/api/v1/me/followed-games/${encodeURIComponent(gameId)}`,
    bearer(accessToken, 'PUT'),
  ));
}

export async function unfollowGame(accessToken: string, gameId: string): Promise<void> {
  const response = await apiFetch(
    `/api/v1/me/followed-games/${encodeURIComponent(gameId)}`,
    bearer(accessToken, 'DELETE'),
  );
  if (!response.ok) await expectOk<never>(response);
}

export async function listMyRules(
  accessToken: string,
  cursor: string | null,
  limit = 20,
): Promise<RulePage> {
  const search = new URLSearchParams({ limit: String(limit) });
  if (cursor) search.set('cursor', cursor);
  return expectOk<RulePage>(await apiFetch(`/api/v1/me/rules?${search.toString()}`, bearer(accessToken)));
}

export async function createRule(
  accessToken: string,
  idempotencyKey: string,
  request: CreateAlertRule,
): Promise<AlertRule> {
  return expectOk<AlertRule>(await apiFetch('/api/v1/me/rules', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Idempotency-Key': idempotencyKey,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  }));
}

export async function updateRule(
  accessToken: string,
  ruleId: string,
  request: UpdateAlertRule,
): Promise<AlertRule> {
  return expectOk<AlertRule>(await apiFetch(`/api/v1/me/rules/${encodeURIComponent(ruleId)}`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }));
}

export async function deleteRule(accessToken: string, ruleId: string): Promise<void> {
  const response = await apiFetch(`/api/v1/me/rules/${encodeURIComponent(ruleId)}`, bearer(accessToken, 'DELETE'));
  if (!response.ok) await expectOk<never>(response);
}

export async function listMyAlerts(
  accessToken: string,
  cursor: string | null,
  limit = 20,
): Promise<OwnedAlertPage> {
  const search = new URLSearchParams({ limit: String(limit) });
  if (cursor) search.set('cursor', cursor);
  return expectOk<OwnedAlertPage>(await apiFetch(`/api/v1/me/alerts?${search.toString()}`, bearer(accessToken)));
}
