import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { alertPage, eventPage, events, gamePage, snapshot } from './fixtures';

export const handlers = [
  http.get('*/api/v1/auth/config', () => HttpResponse.json({
    enabled: false,
    issuer: '',
    clientId: 'courtpulse-web',
    scope: 'openid profile',
  })),
  http.get('*/api/v1/games', () => HttpResponse.json(gamePage())),
  http.get('*/api/v1/games/:gameId', () => HttpResponse.json(snapshot, { headers: { ETag: '"snapshot-v1"' } })),
  http.get('*/api/v1/games/:gameId/events', ({ request }) => {
    const cursor = new URL(request.url).searchParams.get('cursor');
    return HttpResponse.json(cursor ? eventPage(events.slice(8, 16), null) : eventPage(events.slice(0, 8), 'page-2'));
  }),
  http.get('*/api/v1/games/:gameId/alerts', () => HttpResponse.json(alertPage())),
];

export const server = setupServer(...handlers);
