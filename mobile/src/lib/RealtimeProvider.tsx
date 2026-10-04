import { useFocusEffect } from 'expo-router';
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState } from 'react-native';
import { acknowledgeConversation, DEV_PET_ID, eventsUrl, getConversation, getConversations,
  getMessages, getUnreadCounts, type UnreadCounts } from './api';
import { parseRealtimeEvent, reconnectDelay, type RealtimeEvent } from './realtimeState';
import { useForegroundPolling } from './useForegroundPolling';

type Listener = (event: RealtimeEvent) => void;
const Context = createContext({ connected: false, unread: { messages: 0, notifications: 0 } as UnreadCounts,
  subscribe: (_listener: Listener): (() => void) => () => {}, refreshUnread: () => {} });
const active = () => !AppState.currentState || AppState.currentState === 'active';

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const [connected, setConnected] = useState(false);
  const [unread, setUnread] = useState<UnreadCounts>({ messages: 0, notifications: 0 });
  const listeners = useRef(new Set<Listener>());
  const refresh = useRef<() => void>(() => {});
  const subscribe = useCallback((listener: Listener) => {
    listeners.current.add(listener);
    return () => { listeners.current.delete(listener); };
  }, []);
  const refreshUnread = useCallback(() => refresh.current(), []);

  useEffect(() => {
    let disposed = false;
    let socket: WebSocket | null = null;
    let reconnect: ReturnType<typeof setTimeout> | undefined;
    let heartbeat: ReturnType<typeof setInterval> | undefined;
    let pongDeadline: ReturnType<typeof setTimeout> | undefined;
    let attempts = 0;
    let syncing = false;
    let syncAgain = false;
    const deliveries = new Map<number, Promise<void>>();

    async function deliver(id: number) {
      if (deliveries.has(id)) return deliveries.get(id);
      const operation = (async () => {
        const detail = await getConversation(id);
        let through = detail.myDeliveredThroughId;
        let more = true;
        while (more && !disposed && active()) {
          const page = await getMessages(id, { afterId: through, limit: 50 });
          through = page.items.at(-1)?.id ?? through;
          // Only content actually fetched by this foreground client is acknowledged.
          if (page.items.length && !disposed && active()) await acknowledgeConversation(id, through, false);
          more = page.nextAfterId !== null;
        }
      })().catch(() => { /* REST synchronization will retry while foregrounded. */ })
        .finally(() => deliveries.delete(id));
      deliveries.set(id, operation);
      return operation;
    }

    async function sync() {
      if (disposed || !active()) return;
      syncAgain = true;
      if (syncing) return;
      syncing = true;
      try {
        do {
          syncAgain = false;
          const counts = await getUnreadCounts();
          if (disposed || !active()) return;
          setUnread(counts);
          if (counts.messages > 0) {
            let page: number | null = 0;
            while (page !== null && !disposed && active()) {
              const result = await getConversations(50, page);
              await Promise.all(result.items.filter(item => item.unreadCount > 0).map(item => deliver(item.id)));
              page = result.nextPage;
            }
          }
        } while (syncAgain && !disposed && active());
      } catch { /* Keep the last counters; PostgreSQL will be queried on the next tick. */ }
      finally { syncing = false; }
    }
    refresh.current = () => { void sync(); };
    function emit(event: RealtimeEvent) {
      for (const listener of listeners.current) listener(event);
      void sync();
      if (event.type === 'MESSAGE' && event.senderPetId !== DEV_PET_ID) void deliver(event.conversationId!);
    }
    function clearHeartbeat() {
      clearInterval(heartbeat);
      clearTimeout(pongDeadline);
      heartbeat = undefined;
      pongDeadline = undefined;
    }
    function connect() {
      if (disposed || !active() || socket) return;
      const current = new WebSocket(eventsUrl);
      socket = current;
      current.onopen = () => {
        if (socket !== current || disposed) return;
        attempts = 0;
        setConnected(true);
        emit({ type: 'SYNC' });
        heartbeat = setInterval(() => {
          if (current.readyState !== WebSocket.OPEN) return;
          current.send('ping');
          pongDeadline = setTimeout(() => current.close(), 10000);
        }, 20000);
      };
      current.onmessage = event => {
        if (socket !== current || disposed || !active()) return;
        const hint = parseRealtimeEvent(String(event.data));
        if (hint?.type === 'PONG') { clearTimeout(pongDeadline); return; }
        if (hint) emit(hint);
      };
      current.onerror = () => current.close();
      current.onclose = () => {
        if (socket !== current) return;
        socket = null;
        clearHeartbeat();
        if (disposed) return;
        setConnected(false);
        if (active()) reconnect = setTimeout(connect, reconnectDelay(attempts++));
      };
    }
    function disconnect() {
      clearTimeout(reconnect);
      clearHeartbeat();
      const previous = socket;
      socket = null;
      previous?.close();
      if (!disposed) setConnected(false);
    }
    connect();
    void sync();
    // Missed hints (including a Redis outage) can never permanently stale PostgreSQL state.
    const fallback = setInterval(() => {
      if (socket?.readyState !== WebSocket.OPEN) void sync();
    }, 5000);
    const reconciliation = setInterval(() => {
      if (active()) { emit({ type: 'SYNC' }); }
    }, 30000);
    const appState = AppState.addEventListener('change', state => {
      if (state === 'active') { connect(); emit({ type: 'SYNC' }); }
      else disconnect();
    });
    return () => {
      disposed = true;
      refresh.current = () => {};
      disconnect();
      clearInterval(fallback);
      clearInterval(reconciliation);
      appState.remove();
    };
  }, []);

  return <Context.Provider value={{ connected, unread, subscribe, refreshUnread }}>{children}</Context.Provider>;
}

export const useRealtime = () => useContext(Context);

export function useRealtimeRefresh(callback: () => Promise<void>, conversationId?: number) {
  const { connected, subscribe } = useRealtime();
  const latest = useRef(callback);
  latest.current = callback;
  useFocusEffect(useCallback(() => {
    let focused = true;
    let busy = false;
    let again = false;
    async function refresh() {
      if (!focused || !active()) return;
      again = true;
      if (busy) return;
      busy = true;
      try {
        do { again = false; await latest.current(); } while (again && focused && active());
      } finally { busy = false; }
    }
    const unsubscribe = subscribe(event => {
      if (event.type === 'SYNC' || conversationId === undefined || event.conversationId === conversationId) void refresh();
    });
    return () => { focused = false; unsubscribe(); };
  }, [subscribe, conversationId]));
  useForegroundPolling(callback, connected ? 30000 : 5000);
}
