import { useRootNavigationState, useRouter } from 'expo-router';
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState } from 'react-native';
import { readNotification, type PushDeviceStatus } from './api';
import { enablePush, listenForPush, pausePush, pushState, pushSupported } from './pushNotifications';
import { notificationTarget } from './realtimeState';
import { useRealtime } from './RealtimeProvider';

const Context = createContext({ supported: pushSupported, busy: false, status: null as PushDeviceStatus | null,
  error: null as string | null, enable: () => {}, pause: () => {} });

export function DevicePushProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const navigation = useRootNavigationState();
  const { refreshUnread } = useRealtime();
  const [status, setStatus] = useState<PushDeviceStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  const refresh = useCallback(async () => {
    if (!pushSupported || inFlight.current) return;
    inFlight.current = true;
    try { const value = await pushState(); if (mounted.current) setStatus(value); }
    catch { /* Explicit enable/pause displays errors; foreground synchronization is best effort. */ }
    finally { inFlight.current = false; }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const listener = AppState.addEventListener('change', state => { if (state === 'active') void refresh(); });
    return () => { mounted.current = false; listener.remove(); };
  }, [refresh]);

  useEffect(() => {
    if (!navigation?.key) return;
    return listenForPush(data => {
      const target = notificationTarget(data);
      if (!target) return;
      void readNotification(target.notificationId).then(item => {
        if (!mounted.current) return;
        refreshUnread();
        if (item.type === 'MESSAGE') router.push({ pathname: '/conversation/[id]', params: { id: String(item.targetId) } });
        else router.push({ pathname: '/task/[id]', params: { id: String(item.targetId) } });
      }).catch(() => {
        if (mounted.current) setError('Could not open the notification. Open Notifications to try again.');
      });
    }, () => { refreshUnread(); void refresh(); });
  }, [navigation?.key, router, refreshUnread, refresh]);

  async function update(enable: boolean) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      if (enable) { const value = await enablePush(); if (mounted.current) setStatus(value); }
      else { await pausePush(); if (mounted.current) setStatus(previous => ({ registered: false, serverEnabled: previous?.serverEnabled ?? false })); }
    } catch (cause) {
      if (mounted.current) setError(cause instanceof Error ? cause.message : 'Could not update device push. Try again.');
    } finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  }

  return <Context.Provider value={{ supported: pushSupported, busy, status, error,
    enable: () => { void update(true); }, pause: () => { void update(false); } }}>{children}</Context.Provider>;
}

export const useDevicePush = () => useContext(Context);
