import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { InfiniteData } from '@tanstack/react-query';
import type { EventPage } from '../api/client';
import { uniqueEvents } from '../api/hooks';
import { DataStatusBadge } from '../components/DataStatusBadge';
import { render } from '@testing-library/react';
import { alertPage, eventPage, events, snapshot } from '../test/fixtures';
import { renderApp } from '../test/render';
import { server } from '../test/server';

function requiredElement(element: HTMLElement | null): HTMLElement {
  if (!element) throw new Error('Expected containing section');
  return element;
}

describe('game detail', () => {
  it('shows the final 18–14 score with semantic team regions', async () => {
    renderApp('/games/game_synthetic_001');
    const home = await screen.findByLabelText('Home team score');
    const away = screen.getByLabelText('Away team score');
    expect(within(home).getByText('18')).toBeVisible();
    expect(within(away).getByText('14')).toBeVisible();
    expect(screen.getByText('Checkpoint v20')).toBeVisible();
  });

  it('renders player_ace with 13 points', async () => {
    renderApp('/games/game_synthetic_001');
    const players = await screen.findByRole('heading', { name: 'Player totals' });
    const panel = players.closest('section');
    expect(panel).not.toBeNull();
    expect(within(requiredElement(panel)).getByText('player ace')).toBeVisible();
    expect(within(requiredElement(panel)).getByText(/13/)).toBeVisible();
  });

  it('renders events in API sequence order', async () => {
    renderApp('/games/game_synthetic_001');
    const list = await screen.findByRole('heading', { name: 'Play by play' });
    const rows = within(requiredElement(list.closest('section'))).getAllByRole('listitem');
    expect(rows.slice(0, 3).map((row) => within(row).getByText(/^[1-3]$/).textContent)).toEqual(['1', '2', '3']);
  });

  it('loads another event page', async () => {
    const user = userEvent.setup();
    renderApp('/games/game_synthetic_001');
    await screen.findByText('8 loaded');
    await user.click(screen.getByRole('button', { name: 'Load more possessions' }));
    expect(await screen.findByText('16 loaded')).toBeVisible();
    expect(screen.getByText('16', { selector: '.event-sequence' })).toBeVisible();
  });

  it('suppresses duplicate events when pages overlap', () => {
    const data: InfiniteData<EventPage> = {
      pages: [eventPage(events.slice(0, 3), 'next'), eventPage(events.slice(2, 5))],
      pageParams: [null, 'next'],
    };
    expect(uniqueEvents(data).map((event) => event.eventId)).toEqual([
      'event-1', 'event-2', 'event-3', 'event-4', 'event-5',
    ]);
  });

  it('shows exactly one milestone alert', async () => {
    renderApp('/games/game_synthetic_001');
    expect(await screen.findByRole('heading', { name: 'player_ace reached 10 points' })).toBeVisible();
    expect(screen.getAllByText(/milestone-player-ace-10/)).toHaveLength(1);
  });

  it('handles a game with no alerts', async () => {
    server.use(http.get('*/api/v1/games/:gameId/alerts', () => HttpResponse.json(alertPage([]))));
    renderApp('/games/game_synthetic_001');
    expect(await screen.findByText('No alerts yet')).toBeVisible();
  });

  it('preserves cached data when refresh returns exact ETag 304', async () => {
    let requests = 0;
    let conditionalHeader: string | null = null;
    server.use(http.get('*/api/v1/games/:gameId', ({ request }) => {
      requests += 1;
      conditionalHeader = request.headers.get('If-None-Match');
      if (requests > 1) return new HttpResponse(null, { status: 304, headers: { ETag: '"v1"' } });
      return HttpResponse.json(snapshot, { headers: { ETag: '"v1"' } });
    }));
    const user = userEvent.setup();
    renderApp('/games/game_synthetic_001');
    await screen.findByText('Checkpoint v20');
    await user.click(screen.getByRole('button', { name: 'Refresh game snapshot' }));
    expect(await screen.findByText('Checkpoint v20')).toBeVisible();
    expect(conditionalHeader).toBe('"v1"');
  });

  it('replaces the snapshot when refresh returns a new ETag', async () => {
    let requests = 0;
    server.use(http.get('*/api/v1/games/:gameId', () => {
      requests += 1;
      return HttpResponse.json(
        requests > 1 ? { ...snapshot, homeScore: 20, stateVersion: 21 } : snapshot,
        { headers: { ETag: requests > 1 ? '"v2"' : '"v1"' } },
      );
    }));
    const user = userEvent.setup();
    renderApp('/games/game_synthetic_001');
    await screen.findByText('Checkpoint v20');
    await user.click(screen.getByRole('button', { name: 'Refresh game snapshot' }));
    expect(await screen.findByText('Checkpoint v21')).toBeVisible();
    expect(within(screen.getByLabelText('Home team score')).getByText('20')).toBeVisible();
  });

  it('shows a safe unknown-game state', async () => {
    server.use(http.get('*/api/v1/games/:gameId', () => HttpResponse.json({
      type: 'https://courtpulse.dev/problems/game_not_found',
      title: 'Game not found',
      status: 404,
      detail: 'Game was not found.',
      instance: '/api/v1/games/missing',
      correlationId: 'not-found-1',
      code: 'game_not_found',
    }, { status: 404, headers: { 'Content-Type': 'application/problem+json' } })));
    renderApp('/games/missing');
    expect(await screen.findByRole('heading', { name: 'That game is not available.' })).toBeVisible();
    expect(screen.queryByText('Game was not found.')).not.toBeInTheDocument();
  });
});

describe('data status language', () => {
  it('renders readable text for every data status without relying on color', () => {
    const statuses = ['SCHEDULED', 'LIVE', 'FINAL', 'STALE', 'PROCESSING_BLOCKED'] as const;
    render(<>{statuses.map((status) => <DataStatusBadge status={status} key={status} />)}</>);
    for (const label of ['Scheduled', 'Live', 'Final', 'Updates delayed', 'Processing attention']) {
      expect(screen.getByText(label)).toBeVisible();
    }
  });

  it('explains stale data safely', () => {
    render(<DataStatusBadge status="STALE" explain />);
    expect(screen.getByText('The game is live, but updates may be delayed.')).toBeVisible();
  });

  it('explains blocked processing without exposing internal failure details', () => {
    render(<DataStatusBadge status="PROCESSING_BLOCKED" explain />);
    expect(screen.getByText(/game processing needs attention/i)).toBeVisible();
    expect(screen.queryByText(/outbox|exception|sql/i)).not.toBeInTheDocument();
  });
});
