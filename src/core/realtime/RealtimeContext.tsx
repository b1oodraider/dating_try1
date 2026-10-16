import React, { createContext, useCallback, useContext, useEffect, useRef } from 'react';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import type { Match, Message } from '../types';

/** Server-Sent Events of GET /events (docs/api-contract.md, Realtime). */
export type RealtimeEvent =
  | { name: 'ready' }
  | { name: 'message'; data: Message }
  | { name: 'match'; data: Match }
  | { name: 'match_closed'; data: { matchId: string } }
  | { name: 'like_received'; data: { superlike: boolean; count: number } }
  | { name: 'read'; data: { matchId: string; lastReadMessageId: string; lastReadAt: string } };

type Listener = (event: RealtimeEvent) => void;

const NAMES = ['ready', 'message', 'match', 'match_closed', 'like_received', 'read'] as const;
const MAX_RETRY_MS = 30_000;

const RealtimeContext = createContext<(listener: Listener) => () => void>(() => () => {});

/**
 * One event stream per signed-in tab. The ticket is one-time, so EventSource's own reconnect cannot be used:
 * every (re)connect takes a fresh ticket. No replay: listeners refetch on `ready`.
 */
export const RealtimeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { account } = useAuth();
  const accountId = account?.id ?? null;
  const listeners = useRef(new Set<Listener>());

  useEffect(() => {
    if (!accountId) return;
    let source: EventSource | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let closed = false;
    let retryMs = 1000;

    const emit = (event: RealtimeEvent) => listeners.current.forEach((listener) => listener(event));

    const reconnectLater = () => {
      if (closed) return;
      timer = setTimeout(connect, retryMs);
      retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
    };

    async function connect() {
      try {
        const { ticket } = await api.getEventsTicket();
        if (closed) return;
        source = new EventSource(`/api/v1/events?ticket=${encodeURIComponent(ticket)}`);
        for (const name of NAMES) {
          source.addEventListener(name, (e) => {
            if (name === 'ready') retryMs = 1000;
            const data = name === 'ready' ? undefined : JSON.parse((e as MessageEvent<string>).data);
            emit({ name, data } as RealtimeEvent);
          });
        }
        source.onerror = () => {
          source?.close();
          source = null;
          reconnectLater();
        };
      } catch {
        reconnectLater();
      }
    }

    void connect();
    return () => {
      closed = true;
      clearTimeout(timer);
      source?.close();
    };
  }, [accountId]);

  const subscribe = useCallback((listener: Listener) => {
    listeners.current.add(listener);
    return () => {
      listeners.current.delete(listener);
    };
  }, []);

  return <RealtimeContext.Provider value={subscribe}>{children}</RealtimeContext.Provider>;
};

/** Calls the latest `listener` for every realtime event while the component is mounted. */
export function useRealtime(listener: Listener): void {
  const subscribe = useContext(RealtimeContext);
  const latest = useRef(listener);
  latest.current = listener;
  useEffect(() => subscribe((event) => latest.current(event)), [subscribe]);
}
