export type RealtimeConnectionState = 'CONNECTED' | 'RECONNECTING' | 'POLLING';

interface ServerMessage {
  schemaVersion: number;
  messageType: string;
  messageId: string;
  gameId: string;
  stateVersion?: number;
  eventId?: string;
  triggerKey?: string;
}

interface RealtimeListener {
  lastStateVersion: () => number | undefined;
  onStateUpdated: () => void;
  onAlertCreated: () => void;
  onResyncRequired: () => void;
  onStatus: (state: RealtimeConnectionState) => void;
}

interface SocketLike {
  readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent<string>) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string): void;
  close(): void;
}

interface ManagedGameConnection {
  gameId: string;
  listeners: Set<RealtimeListener>;
  socket: SocketLike | null;
  state: RealtimeConnectionState;
  reconnectAttempt: number;
  reconnectTimer: number | null;
  disconnectTimer: number | null;
  highestVersion: number;
  seenMessageIds: Set<string>;
  hasConnected: boolean;
}

type SocketFactory = (url: string) => SocketLike;

const OPEN = 1;
const MAX_SEEN_MESSAGES = 256;

export class RealtimeConnectionManager {
  private readonly games = new Map<string, ManagedGameConnection>();

  constructor(
    private readonly socketFactory: SocketFactory = (url) => new WebSocket(url),
    private readonly random: () => number = Math.random,
  ) {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        for (const connection of this.games.values()) {
          if (connection.listeners.size > 0 && connection.socket === null) this.open(connection);
        }
      }
    });
  }

  connect(gameId: string, listener: RealtimeListener): () => void {
    let connection = this.games.get(gameId);
    if (!connection) {
      connection = {
        gameId,
        listeners: new Set(),
        socket: null,
        state: 'RECONNECTING',
        reconnectAttempt: 0,
        reconnectTimer: null,
        disconnectTimer: null,
        highestVersion: listener.lastStateVersion() ?? 0,
        seenMessageIds: new Set(),
        hasConnected: false,
      };
      this.games.set(gameId, connection);
    }
    if (connection.disconnectTimer !== null) {
      window.clearTimeout(connection.disconnectTimer);
      connection.disconnectTimer = null;
    }
    connection.listeners.add(listener);
    listener.onStatus(connection.state);
    if (connection.socket === null && connection.reconnectTimer === null) this.open(connection);

    return () => {
      const current = this.games.get(gameId);
      if (!current) return;
      current.listeners.delete(listener);
      if (current.listeners.size === 0) {
        current.disconnectTimer = window.setTimeout(() => this.dispose(gameId), 0);
      }
    };
  }

  isConnected(gameId: string): boolean {
    return this.games.get(gameId)?.state === 'CONNECTED';
  }

  private open(connection: ManagedGameConnection): void {
    if (connection.listeners.size === 0 || document.visibilityState === 'hidden') {
      this.setState(connection, 'POLLING');
      return;
    }
    this.setState(connection, 'RECONNECTING');
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    let socket: SocketLike;
    try {
      socket = this.socketFactory(`${scheme}//${window.location.host}/ws/v1/games`);
    } catch {
      this.setState(connection, 'POLLING');
      return;
    }
    connection.socket = socket;
    socket.onopen = () => {
      const isReconnect = connection.hasConnected;
      connection.reconnectAttempt = 0;
      const versions = [...connection.listeners]
        .map((listener) => listener.lastStateVersion())
        .filter((version): version is number => version !== undefined);
      connection.highestVersion = Math.max(connection.highestVersion, ...versions, 0);
      socket.send(JSON.stringify({
        schemaVersion: 1,
        messageType: 'SUBSCRIBE',
        gameId: connection.gameId,
        lastStateVersion: connection.highestVersion,
      }));
      connection.hasConnected = true;
      this.setState(connection, 'CONNECTED');
      if (isReconnect) this.resync(connection);
    };
    socket.onmessage = (event) => this.receive(connection, event.data);
    socket.onerror = () => socket.close();
    socket.onclose = () => {
      if (connection.socket === socket) connection.socket = null;
      if (connection.listeners.size > 0) this.scheduleReconnect(connection);
    };
  }

  private receive(connection: ManagedGameConnection, raw: string): void {
    let message: ServerMessage;
    try {
      message = JSON.parse(raw) as ServerMessage;
    } catch {
      this.resync(connection);
      return;
    }
    if (message.schemaVersion !== 1 || message.gameId !== connection.gameId || !message.messageId) {
      this.resync(connection);
      return;
    }
    if (connection.seenMessageIds.has(message.messageId)) return;
    connection.seenMessageIds.add(message.messageId);
    if (connection.seenMessageIds.size > MAX_SEEN_MESSAGES) {
      const oldest = connection.seenMessageIds.values().next().value;
      if (oldest !== undefined) connection.seenMessageIds.delete(oldest);
    }

    if (message.messageType === 'RESYNC_REQUIRED') {
      if (message.stateVersion !== undefined) connection.highestVersion = message.stateVersion;
      this.resync(connection);
      return;
    }
    if (message.messageType === 'PROBLEM') {
      this.setState(connection, 'POLLING');
      return;
    }
    if (message.messageType === 'SUBSCRIPTION_ACKNOWLEDGED') return;
    if (message.stateVersion === undefined || message.stateVersion < 0) {
      this.resync(connection);
      return;
    }
    if (message.stateVersion < connection.highestVersion) return;
    if (message.stateVersion > connection.highestVersion + 1) {
      connection.highestVersion = message.stateVersion;
      this.resync(connection);
      return;
    }
    connection.highestVersion = Math.max(connection.highestVersion, message.stateVersion);
    for (const listener of connection.listeners) {
      if (message.messageType === 'GAME_STATE_UPDATED') listener.onStateUpdated();
      if (message.messageType === 'ALERT_CREATED') listener.onAlertCreated();
    }
  }

  private resync(connection: ManagedGameConnection): void {
    for (const listener of connection.listeners) listener.onResyncRequired();
  }

  private scheduleReconnect(connection: ManagedGameConnection): void {
    if (document.visibilityState === 'hidden') {
      this.setState(connection, 'POLLING');
      return;
    }
    this.setState(connection, 'RECONNECTING');
    const exponential = Math.min(15_000, 500 * 2 ** Math.min(connection.reconnectAttempt, 5));
    const jittered = Math.round(exponential * (0.85 + this.random() * 0.3));
    connection.reconnectAttempt += 1;
    connection.reconnectTimer = window.setTimeout(() => {
      connection.reconnectTimer = null;
      this.open(connection);
    }, jittered);
  }

  private setState(connection: ManagedGameConnection, state: RealtimeConnectionState): void {
    connection.state = state;
    for (const listener of connection.listeners) listener.onStatus(state);
  }

  private dispose(gameId: string): void {
    const connection = this.games.get(gameId);
    if (!connection || connection.listeners.size > 0) return;
    if (connection.reconnectTimer !== null) window.clearTimeout(connection.reconnectTimer);
    if (connection.socket?.readyState === OPEN) connection.socket.close();
    connection.socket = null;
    this.games.delete(gameId);
  }
}

export const realtimeManager = new RealtimeConnectionManager();
