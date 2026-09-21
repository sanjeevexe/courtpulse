import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  RealtimeConnectionManager,
  type RealtimeConnectionState,
} from './client';

class FakeSocket {
  readyState = 0;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  sent: string[] = [];
  closed = false;

  send(data: string) { this.sent.push(data); }
  close() { this.closed = true; this.onclose?.({} as CloseEvent); }
  open() { this.readyState = 1; this.onopen?.(new Event('open')); }
  receive(message: object) {
    this.onmessage?.({ data: JSON.stringify(message) } as MessageEvent<string>);
  }
}

function serverMessage(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    schemaVersion: 1,
    messageType: 'GAME_STATE_UPDATED',
    messageId: 'message-1',
    gameId: 'game-1',
    stateVersion: 6,
    emittedAt: '2026-09-21T12:00:00Z',
    correlationId: 'trace-1',
    eventId: 'event-6',
    ...overrides,
  };
}

function listener(version = 5) {
  return {
    lastStateVersion: () => version,
    onStateUpdated: vi.fn(),
    onAlertCreated: vi.fn(),
    onResyncRequired: vi.fn(),
    onStatus: vi.fn<(state: RealtimeConnectionState) => void>(),
  };
}

afterEach(() => {
  vi.useRealTimers();
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
});

describe('realtime connection manager', () => {
  it('shares one connection for subscribers to the active game', () => {
    const sockets: FakeSocket[] = [];
    const manager = new RealtimeConnectionManager(() => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    }, () => 0.5);
    const first = listener();
    const second = listener();
    manager.connect('game-1', first);
    manager.connect('game-1', second);
    expect(sockets).toHaveLength(1);
    sockets[0]?.open();
    expect(JSON.parse(sockets[0]?.sent[0] ?? '{}')).toMatchObject({
      schemaVersion: 1,
      messageType: 'SUBSCRIBE',
      gameId: 'game-1',
      lastStateVersion: 5,
    });
  });

  it('disconnects after the final game-page subscriber navigates away', () => {
    vi.useFakeTimers();
    const socket = new FakeSocket();
    const manager = new RealtimeConnectionManager(() => socket, () => 0.5);
    const disconnect = manager.connect('game-1', listener());
    socket.open();
    disconnect();
    vi.runAllTimers();
    expect(socket.closed).toBe(true);
  });

  it('suppresses duplicate message identities and older versions', () => {
    const socket = new FakeSocket();
    const target = listener();
    const manager = new RealtimeConnectionManager(() => socket, () => 0.5);
    manager.connect('game-1', target);
    socket.open();
    socket.receive(serverMessage());
    socket.receive(serverMessage());
    socket.receive(serverMessage({ messageId: 'old', stateVersion: 4 }));
    expect(target.onStateUpdated).toHaveBeenCalledTimes(1);
  });

  it('requests authoritative HTTP resynchronization when a version gap is observed', () => {
    const socket = new FakeSocket();
    const target = listener();
    const manager = new RealtimeConnectionManager(() => socket, () => 0.5);
    manager.connect('game-1', target);
    socket.open();
    socket.receive(serverMessage({ stateVersion: 8 }));
    expect(target.onResyncRequired).toHaveBeenCalledOnce();
    expect(target.onStateUpdated).not.toHaveBeenCalled();
  });

  it('refreshes alerts once when a duplicate alert hint is redelivered', () => {
    const socket = new FakeSocket();
    const target = listener();
    const manager = new RealtimeConnectionManager(() => socket, () => 0.5);
    manager.connect('game-1', target);
    socket.open();
    const alert = serverMessage({
      messageType: 'ALERT_CREATED',
      triggerKey: 'player-ace:10',
      eventId: undefined,
    });
    socket.receive(alert);
    socket.receive(alert);
    expect(target.onAlertCreated).toHaveBeenCalledOnce();
  });

  it('uses bounded reconnect backoff after an interruption', () => {
    vi.useFakeTimers();
    const sockets: FakeSocket[] = [];
    const target = listener();
    const manager = new RealtimeConnectionManager(() => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    }, () => 0.5);
    manager.connect('game-1', target);
    sockets[0]?.open();
    sockets[0]?.close();
    expect(target.onStatus).toHaveBeenLastCalledWith('RECONNECTING');
    vi.advanceTimersByTime(500);
    expect(sockets).toHaveLength(2);
    sockets[1]?.open();
    expect(target.onResyncRequired).toHaveBeenCalledOnce();
    expect(target.onStatus).toHaveBeenLastCalledWith('CONNECTED');
  });

  it('falls back to polling while hidden and reconnects when visible', () => {
    const sockets: FakeSocket[] = [];
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    const target = listener();
    const manager = new RealtimeConnectionManager(() => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    }, () => 0.5);
    manager.connect('game-1', target);
    expect(sockets).toHaveLength(0);
    expect(target.onStatus).toHaveBeenLastCalledWith('POLLING');
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(sockets).toHaveLength(1);
  });

  it('treats malformed server data as a resynchronization signal', () => {
    const socket = new FakeSocket();
    const target = listener();
    const manager = new RealtimeConnectionManager(() => socket, () => 0.5);
    manager.connect('game-1', target);
    socket.open();
    socket.onmessage?.({ data: '{not-json' } as MessageEvent<string>);
    expect(target.onResyncRequired).toHaveBeenCalledOnce();
  });
});
