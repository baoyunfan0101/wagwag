import { useFocusEffect } from 'expo-router';
import { useCallback, useRef } from 'react';
import { AppState } from 'react-native';

export function useForegroundPolling(callback: () => Promise<void>, intervalMs = 5000) {
  const latest = useRef(callback);
  latest.current = callback;
  const inFlight = useRef(false);

  useFocusEffect(useCallback(() => {
    let focused = true;
    async function tick() {
      if (!focused || inFlight.current || (AppState.currentState && AppState.currentState !== 'active')) return;
      inFlight.current = true;
      try { await latest.current(); }
      finally { inFlight.current = false; }
    }
    const timer = setInterval(() => { void tick(); }, intervalMs);
    const listener = AppState.addEventListener('change', state => { if (state === 'active') void tick(); });
    return () => { focused = false; clearInterval(timer); listener.remove(); };
  }, [intervalMs]));
}
