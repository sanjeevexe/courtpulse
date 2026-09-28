// Public read API under an open-model arrival rate. Every request is a real HTTP call against
// the durable PostgreSQL-backed API; conditional GETs exercise the snapshot ETag path.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PEAK_RATE = Number(__ENV.PEAK_RATE || 200);
const notModified = new Rate('snapshot_not_modified');

export const options = {
  discardResponseBodies: false,
  scenarios: {
    browse: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 400,
      stages: [
        { target: PEAK_RATE / 2, duration: '20s' },
        { target: PEAK_RATE, duration: '60s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:games}': ['p(95)<250'],
    'http_req_duration{endpoint:snapshot}': ['p(95)<250'],
    'http_req_duration{endpoint:events}': ['p(95)<300'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const games = http.get(`${BASE_URL}/api/v1/games?limit=100`).json('items') || [];
  if (games.length === 0) throw new Error('No games are seeded; run the load harness seed step first');
  return { gameIds: games.map((game) => game.gameId) };
}

const etags = {};

export default function (data) {
  const gameId = data.gameIds[Math.floor(Math.random() * data.gameIds.length)];
  const list = http.get(`${BASE_URL}/api/v1/games?limit=25`, { tags: { endpoint: 'games' } });
  check(list, { 'games 200': (response) => response.status === 200 });

  const headers = etags[gameId] ? { 'If-None-Match': etags[gameId] } : {};
  const snapshot = http.get(`${BASE_URL}/api/v1/games/${gameId}`, { headers, tags: { endpoint: 'snapshot' } });
  check(snapshot, { 'snapshot 200/304': (response) => response.status === 200 || response.status === 304 });
  notModified.add(snapshot.status === 304);
  if (snapshot.headers.Etag) etags[gameId] = snapshot.headers.Etag;

  const events = http.get(`${BASE_URL}/api/v1/games/${gameId}/events?limit=25`, { tags: { endpoint: 'events' } });
  check(events, { 'events 200': (response) => response.status === 200 });
}
