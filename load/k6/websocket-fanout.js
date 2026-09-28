// Sustained WebSocket subscribers while a paced replay produces hints. Latency is measured from
// the server's emittedAt to receipt in this process (same host clock), i.e. hub fanout delay.
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws/v1/games';
const GAME_ID = __ENV.GAME_ID || 'load-game-0001';
const CLIENTS = Number(__ENV.CLIENTS || 2000);
const HOLD_SECONDS = Number(__ENV.HOLD_SECONDS || 120);

const hintLatency = new Trend('realtime_hint_latency', true);
const hints = new Counter('realtime_hints_received');
const resyncs = new Counter('realtime_resync_required');
const problems = new Counter('realtime_problems');

export const options = {
  scenarios: {
    subscribers: {
      executor: 'per-vu-iterations',
      vus: CLIENTS,
      iterations: 1,
      maxDuration: `${HOLD_SECONDS + 60}s`,
    },
  },
  thresholds: {
    realtime_hint_latency: ['p(95)<2000'],
    realtime_problems: ['count==0'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Java serializes Instants with up to nanosecond precision; the k6 runtime's Date.parse only
// understands milliseconds, so trim extra fraction digits before parsing.
function emittedMillis(value) {
  return Date.parse(String(value).replace(/(\.\d{3})\d+/, '$1'));
}

export default function () {
  // Spread connection setup over ~20 seconds, as real clients arrive.
  sleep(Math.random() * 20);
  const response = ws.connect(WS_URL, {}, (socket) => {
    socket.on('open', () => {
      socket.send(JSON.stringify({ schemaVersion: 1, messageType: 'SUBSCRIBE', gameId: GAME_ID, lastStateVersion: 0 }));
    });
    socket.on('message', (raw) => {
      const message = JSON.parse(raw);
      if (message.messageType === 'GAME_STATE_UPDATED' || message.messageType === 'ALERT_CREATED') {
        hints.add(1);
        const emittedAt = emittedMillis(message.emittedAt);
        if (Number.isFinite(emittedAt)) {
          hintLatency.add(Date.now() - emittedAt);
        }
      } else if (message.messageType === 'RESYNC_REQUIRED') {
        resyncs.add(1);
      } else if (message.messageType === 'PROBLEM') {
        problems.add(1);
      }
    });
    socket.setTimeout(() => socket.close(), HOLD_SECONDS * 1000);
  });
  check(response, { 'upgraded to WebSocket': (result) => result && result.status === 101 });
}
