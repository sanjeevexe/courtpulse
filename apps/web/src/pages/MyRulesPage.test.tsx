import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AuthContext, type AuthContextValue } from '../auth/AuthContext';
import { server } from '../test/server';
import { MyAlertsPage } from './MyAlertsPage';
import { MyRulesPage } from './MyRulesPage';

const auth: AuthContextValue = {
  status: 'AUTHENTICATED', enabled: true, subject: 'user-a', accessToken: 'access-token', error: null,
  signInPending: false, signOutPending: false,
  signIn: () => Promise.resolve(), completeCallback: () => Promise.resolve('/'), signOut: () => Promise.resolve(),
};

const playerRule = {
  id: '11111111-1111-4111-8111-111111111111', type: 'PLAYER_POINTS' as const,
  gameId: 'game_synthetic_001', enabled: true, playerId: 'player_ace', pointsThreshold: 10,
  version: 1, createdAt: '2026-09-21T20:00:00Z', updatedAt: '2026-09-21T20:00:00Z',
};

function renderPrivate(page: 'rules' | 'alerts') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={auth}>
        <MemoryRouter>{page === 'rules' ? <MyRulesPage /> : <MyAlertsPage />}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe('My Rules', () => {
  it('offers only structured templates and validates type-specific fields', async () => {
    server.use(http.get('*/api/v1/me/rules', () => HttpResponse.json({ items: [] })));
    const user = userEvent.setup();
    renderPrivate('rules');
    expect(await screen.findByRole('heading', { name: 'Create a rule' })).toBeVisible();
    expect(screen.getByText(/never executes user-authored expressions/i)).toBeVisible();
    expect(screen.queryByLabelText(/expression/i)).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Template'), 'SCORING_RUN');
    expect(screen.getByText(/opponent score ends the run/i)).toBeVisible();
    await user.type(screen.getByLabelText('Game ID'), 'game_synthetic_001');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Team ID is required');
  });

  it('creates, disables, and deletes an owned rule with bearer authentication', async () => {
    let rules = [playerRule];
    server.use(
      http.get('*/api/v1/me/rules', ({ request }) => {
        expect(request.headers.get('Authorization')).toBe('Bearer access-token');
        return HttpResponse.json({ items: rules });
      }),
      http.post('*/api/v1/me/rules', async ({ request }) => {
        expect(request.headers.get('Idempotency-Key')).toBeTruthy();
        const body = await request.json() as { type: string; playerId: string };
        expect(body).toMatchObject({ type: 'PLAYER_POINTS', playerId: 'player_new' });
        rules = [{ ...playerRule, id: '22222222-2222-4222-8222-222222222222', playerId: 'player_new' }, ...rules];
        return HttpResponse.json(rules[0], { status: 201 });
      }),
      http.patch('*/api/v1/me/rules/:ruleId', async ({ request }) => {
        const body = await request.json() as { enabled: boolean; version: number };
        expect(body).toEqual({ enabled: false, version: 1 });
        rules = rules.map((rule) => rule.id === playerRule.id ? { ...rule, enabled: false, version: 2 } : rule);
        return HttpResponse.json(rules.find((rule) => rule.id === playerRule.id));
      }),
      http.delete('*/api/v1/me/rules/:ruleId', ({ params }) => {
        rules = rules.filter((rule) => rule.id !== params.ruleId);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderPrivate('rules');
    expect(await screen.findByText('player_ace · 10 points')).toBeVisible();
    await user.type(screen.getByLabelText('Game ID'), 'game_synthetic_001');
    await user.type(screen.getByLabelText('Player ID'), 'player_new');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(await screen.findByText('player_new · 10 points')).toBeVisible();
    const firstCard = screen.getByText('player_ace · 10 points').closest('article');
    if (!firstCard) throw new Error('rule card missing');
    await user.click(within(firstCard).getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(screen.getByText('player_ace · 10 points')).toBeVisible());
    const refreshedCard = screen.getByText('player_ace · 10 points').closest('article');
    if (!refreshedCard) throw new Error('updated rule card missing');
    await user.click(within(refreshedCard).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(screen.queryByText('player_ace · 10 points')).not.toBeInTheDocument());
  });

  it('reuses a create key after an uncertain response and starts a new key after success', async () => {
    const keys: string[] = [];
    let attempts = 0;
    server.use(
      http.get('*/api/v1/me/rules', () => HttpResponse.json({ items: [] })),
      http.post('*/api/v1/me/rules', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key') ?? '');
        attempts++;
        if (attempts === 1) return HttpResponse.error();
        return HttpResponse.json(playerRule, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderPrivate('rules');
    await user.type(screen.getByLabelText('Game ID'), 'game_synthetic_001');
    await user.type(screen.getByLabelText('Player ID'), 'player_ace');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not reach the server');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    expect(await screen.findByText('Rule saved.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(keys[2]).not.toBe(keys[0]);
  });
});

describe('My Alerts', () => {
  it('renders private alert history and its immutable trigger facts', async () => {
    server.use(http.get('*/api/v1/me/alerts', ({ request }) => {
      expect(request.headers.get('Authorization')).toBe('Bearer access-token');
      return HttpResponse.json({ items: [{
        id: '33333333-3333-4333-8333-333333333333', ruleId: playerRule.id,
        ruleType: 'PLAYER_POINTS', gameId: 'game_synthetic_001', triggeringEventId: 'event-16',
        title: 'player_ace reached 10 points', context: { threshold: '10', verifiedTotal: '11' },
        status: 'CREATED', createdAt: '2026-09-21T20:00:00Z',
      }] });
    }));
    renderPrivate('alerts');
    expect(await screen.findByRole('heading', { name: 'player_ace reached 10 points' })).toBeVisible();
    expect(screen.getByText(/threshold: 10/)).toBeVisible();
    expect(screen.getByText(/verifiedTotal: 11/)).toBeVisible();
  });
});
