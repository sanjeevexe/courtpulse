import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { ReplayGame, ReplaySession } from '../api/client';
import { AuthContext, type AuthContextValue } from '../auth/AuthContext';
import { ReplayControls } from '../components/ReplayControls';
import { replayLength } from '../lib/replay';
import { server } from '../test/server';
import { ReplaysPage } from './ReplaysPage';

const game: ReplayGame = {
  nbaGameId: '0042500222', gameDate: '2026-05-08', homeTeamName: 'Oklahoma City Thunder',
  homeTeamAbbreviation: 'OKC', awayTeamName: 'Los Angeles Lakers', awayTeamAbbreviation: 'LAL',
  homeScore: 125, awayScore: 107, periods: 5, plays: 584, durationSeconds: 5_400,
};
const session: ReplaySession = {
  sessionId: '11111111-1111-4111-8111-111111111111', nbaGameId: game.nbaGameId,
  gameId: 'nba-replay-0042500222-1', speed: 30, status: 'RUNNING', playsReleased: 146, totalPlays: 584,
  homeTeamName: game.homeTeamName, awayTeamName: game.awayTeamName, gameDate: game.gameDate,
  startedAt: '2026-09-30T12:00:00Z',
};

function auth(authenticated: boolean): AuthContextValue {
  return {
    status: authenticated ? 'AUTHENTICATED' : 'ANONYMOUS', enabled: true,
    subject: authenticated ? 'fan-a' : null, displayName: authenticated ? 'fan-a' : null,
    accessToken: authenticated ? 'access-token' : null, error: null, signInPending: false, signOutPending: false,
    signIn: vi.fn(() => Promise.resolve()), completeCallback: () => Promise.resolve('/'),
    signOut: () => Promise.resolve(),
  };
}

function GameRoute() {
  const { gameId = '' } = useParams();
  return <><h1>Game {gameId}</h1><ReplayControls gameId={gameId} /></>;
}

function renderAt(path: string, value: AuthContextValue) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/replays" element={<ReplaysPage />} />
            <Route path="/games/:gameId" element={<GameRoute />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('Replays', () => {
  it('lists real games with the final, overtime, and replay length, and asks anonymous fans to sign in', async () => {
    server.use(http.get('*/api/v1/replays/games', () => HttpResponse.json({ items: [game] })));
    const anonymous = auth(false);
    const user = userEvent.setup();
    renderAt('/replays', anonymous);

    expect(await screen.findByText('Los Angeles Lakers')).toBeVisible();
    expect(screen.getByText('125')).toBeVisible();
    expect(screen.getByText('Final/OT')).toBeVisible();
    expect(screen.getByText(/584 plays · about 3 min at 30×/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Sign in to replay' }));
    expect(anonymous.signIn).toHaveBeenCalledWith('/replays');
  });

  it('starts a replay at the chosen speed and opens the game', async () => {
    let body: unknown;
    server.use(
      http.get('*/api/v1/replays/games', () => HttpResponse.json({ items: [game] })),
      http.post('*/api/v1/me/replays', async ({ request }) => {
        expect(request.headers.get('Authorization')).toBe('Bearer access-token');
        body = await request.json();
        return HttpResponse.json({ ...session, speed: 60 }, { status: 201 });
      }),
      http.get('*/api/v1/me/replays', () => HttpResponse.json({ items: [] })),
    );
    const user = userEvent.setup();
    renderAt('/replays', auth(true));

    await user.selectOptions(await screen.findByLabelText('Speed'), '60');
    await user.click(screen.getByRole('button', { name: 'Replay' }));

    expect(await screen.findByRole('heading', { name: `Game ${session.gameId}` })).toBeVisible();
    expect(body).toEqual({ nbaGameId: game.nbaGameId, speed: 60 });
  });

  it('shows that the replay is starting and opens the game once it exists', async () => {
    let lookups = 0;
    server.use(
      http.get('*/api/v1/replays/games', () => HttpResponse.json({ items: [game] })),
      http.post('*/api/v1/me/replays', () => HttpResponse.json(session, { status: 201 })),
      http.get('*/api/v1/me/replays', () => HttpResponse.json({ items: [] })),
      http.get(`*/api/v1/games/${session.gameId}`, () => {
        lookups++;
        return lookups === 1
          ? HttpResponse.json({ type: 'about:blank', title: 'Game not found', status: 404, detail: 'Game not found',
            instance: '/', correlationId: 'id', code: 'game_not_found' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } })
          : HttpResponse.json({ gameId: session.gameId });
      }),
    );
    const user = userEvent.setup();
    renderAt('/replays', auth(true));

    await user.click(await screen.findByRole('button', { name: 'Replay' }));
    expect(await screen.findByRole('button', { name: 'Starting replay…' })).toBeVisible();
    expect(await screen.findByRole('heading', { name: `Game ${session.gameId}` }, { timeout: 3_000 })).toBeVisible();
    expect(lookups).toBe(2);
  });

  it('gives the owner pause, speed, and skip controls on the game page', async () => {
    const calls: string[] = [];
    server.use(
      http.get('*/api/v1/replays/sessions', () => HttpResponse.json({ items: [session] })),
      http.get('*/api/v1/me/replays', () => HttpResponse.json({ items: [session] })),
      http.post('*/api/v1/me/replays/:id/:action', ({ params }) => {
        calls.push(String(params.action));
        return HttpResponse.json({ ...session, status: 'PAUSED' });
      }),
      http.put('*/api/v1/me/replays/:id/speed', async ({ request }) => {
        calls.push(`speed ${String(((await request.json()) as { speed: number }).speed)}`);
        return HttpResponse.json({ ...session, speed: 120 });
      }),
    );
    const user = userEvent.setup();
    renderAt(`/games/${session.gameId}`, auth(true));

    expect(await screen.findByText('Replaying at 30×')).toBeVisible();
    expect(screen.getByText('146 of 584 plays (25%)')).toBeVisible();
    await user.click(await screen.findByRole('button', { name: 'Pause' }));
    await user.click(screen.getByRole('button', { name: '120×' }));
    await user.click(screen.getByRole('button', { name: 'Skip to final' }));
    await waitFor(() => expect(calls).toEqual(['pause', 'speed 120', 'finish']));
  });

  it('shows another fan only the option to start their own replay', async () => {
    server.use(
      http.get('*/api/v1/replays/sessions', () => HttpResponse.json({ items: [session] })),
      http.get('*/api/v1/me/replays', () => HttpResponse.json({ items: [] })),
    );
    renderAt(`/games/${session.gameId}`, auth(true));

    expect(await screen.findByRole('button', { name: 'Replay this game yourself' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Pause' })).not.toBeInTheDocument();
  });

  it('estimates replay length for display', () => {
    expect(replayLength(5_400, 30)).toBe('about 3 min');
    expect(replayLength(5_400, 120)).toBe('about 45 s');
    expect(replayLength(9_000, 1)).toBe('about 2.5 h');
  });
});
