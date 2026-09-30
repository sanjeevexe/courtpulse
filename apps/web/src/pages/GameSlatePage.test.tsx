import { delay, http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { server } from '../test/server';
import { game, gamePage } from '../test/fixtures';
import { renderApp } from '../test/render';

describe('game slate', () => {
  it('renders the seeded game and score', async () => {
    renderApp();
    expect(await screen.findByRole('heading', { name: "Today’s pulse" })).toBeVisible();
    expect(await screen.findByText('AWAY')).toBeVisible();
    const card = screen.getByRole('article');
    expect(within(card).getByText('18')).toBeVisible();
    expect(within(card).getByText('14')).toBeVisible();
    expect(within(card).getByRole('link', { name: /view game/i })).toHaveAttribute(
      'href',
      '/games/game_synthetic_001',
    );
  });

  it('sends the selected status filter', async () => {
    let requestedStatus: string | null = null;
    server.use(http.get('*/api/v1/games', ({ request }) => {
      requestedStatus = new URL(request.url).searchParams.get('status');
      return HttpResponse.json(gamePage([]));
    }));
    const user = userEvent.setup();
    renderApp();
    await user.click(screen.getByRole('button', { name: 'Live' }));
    expect(await screen.findByText(/no live games yet/i)).toBeVisible();
    expect(requestedStatus).toBe('LIVE');
  });

  it('loads the next game cursor without replacing the first page', async () => {
    const second = { ...game, gameId: 'game-2', homeTeamId: 'team_north', awayTeamId: 'team_south' };
    server.use(http.get('*/api/v1/games', ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      return HttpResponse.json(cursor ? gamePage([second]) : gamePage([game], 'next-games'));
    }));
    const user = userEvent.setup();
    renderApp();
    await screen.findByText('HOME');
    await user.click(screen.getByRole('button', { name: 'Load more games' }));
    expect(await screen.findByText('NORTH')).toBeVisible();
    expect(screen.getByText('HOME')).toBeVisible();
  });

  it('shows an intentional empty state', async () => {
    server.use(http.get('*/api/v1/games', () => HttpResponse.json(gamePage([]))));
    renderApp();
    expect(await screen.findByRole('heading', { name: /no games yet/i })).toBeVisible();
  });

  it('announces a loading skeleton', () => {
    server.use(http.get('*/api/v1/games', async () => {
      await delay('infinite');
      return HttpResponse.json(gamePage());
    }));
    renderApp();
    expect(screen.getByRole('status', { name: 'Loading games' })).toBeVisible();
  });

  it('maps Problem Details and exposes the correlation ID as technical detail', async () => {
    server.use(http.get('*/api/v1/games', () => HttpResponse.json({
      type: 'https://courtpulse.dev/problems/database_unavailable',
      title: 'Service unavailable',
      status: 503,
      detail: 'Durable CourtPulse data is temporarily unavailable.',
      instance: '/api/v1/games',
      correlationId: 'support-503',
      code: 'database_unavailable',
    }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } })));
    const user = userEvent.setup();
    renderApp();
    expect(await screen.findByRole('heading', { name: 'Game data is temporarily unavailable.' })).toBeVisible();
    await user.click(screen.getByText('Technical details'));
    expect(screen.getByText('support-503')).toBeVisible();
  });

  it('supports keyboard navigation across filters and game links', async () => {
    const user = userEvent.setup();
    renderApp();
    await screen.findByText('HOME');
    await user.tab();
    expect(screen.getByRole('link', { name: 'CourtPulse game slate' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Replays' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'All games' })).toHaveFocus();
  });

  it('provides a safe route-level not-found state and a compact mobile-ready shell', () => {
    renderApp('/missing-route');
    expect(screen.getByRole('heading', { name: 'This route is off the court' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Return to game slate' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('banner')).toHaveClass('site-header');
  });
});
