import { Ionicons } from '@expo/vector-icons';
import * as Crypto from 'expo-crypto';
import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import { useNavigation, useRouter } from 'expo-router';
import { usePreventRemove } from 'expo-router/react-navigation';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, AppState, Platform, Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import WalkMap from '@/components/WalkMap';
import { createWalk, type WalkInput } from '@/lib/api';
import { clearSavedWalk, discardWalkDraft, loadWalkDraft, startWalkTracking, stopWalkTracking,
  subscribeWalk, type WalkDraft } from '@/lib/walkTracking';
import { colors } from '@/lib/theme';

type Phase = 'idle' | 'starting' | 'tracking' | 'saving' | 'saveFailed';

export default function LiveWalkScreen() {
  const router = useRouter();
  const navigation = useNavigation();
  const [phase, setPhase] = useState<Phase>('idle');
  const phaseRef = useRef<Phase>('idle');
  const [loadingDraft, setLoadingDraft] = useState(true);
  const [allowNavigation, setAllowNavigation] = useState(false);
  const [draft, setDraft] = useState<WalkDraft | null>(null);
  const [background, setBackground] = useState(Platform.OS !== 'web');
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const pendingRef = useRef<WalkInput | null>(null);
  const blockedNavigationRef = useRef<(() => void) | null>(null);
  const savedWalkIdRef = useRef<number | null>(null);
  const startGenerationRef = useRef(0);
  const mountedRef = useRef(true);
  const discardingRef = useRef(false);

  function transition(next: Phase) { phaseRef.current = next; setPhase(next); }

  usePreventRemove((loadingDraft || phase !== 'idle') && !allowNavigation, ({ data }) => {
    if (loadingDraft || phaseRef.current === 'saving' || discardingRef.current) {
      if (Platform.OS !== 'web') Alert.alert('Please wait', 'Your route is being updated.');
      return;
    }
    if (blockedNavigationRef.current) return;
    blockedNavigationRef.current = () => navigation.dispatch(data.action);
    confirmDiscard();
  });

  useEffect(() => {
    if (!allowNavigation) return;
    // Replay a blocked action only after the removal guard has been disabled.
    if (savedWalkIdRef.current !== null) {
      const id = savedWalkIdRef.current;
      savedWalkIdRef.current = null;
      router.replace({ pathname: '/walk/[id]', params: { id: String(id) } });
    } else if (blockedNavigationRef.current) {
      const continueNavigation = blockedNavigationRef.current;
      blockedNavigationRef.current = null;
      continueNavigation();
    }
  }, [allowNavigation, router]);

  useEffect(() => {
    mountedRef.current = true;
    function update(value: WalkDraft | null) {
      if (!mountedRef.current) return;
      setDraft(value);
      if (phaseRef.current === 'starting' || phaseRef.current === 'saving' || discardingRef.current) return;
      transition(value ? value.endedAt ? 'saveFailed' : 'tracking' : 'idle');
      if (value?.endedAt && value.points.length) pendingRef.current = {
        clientWalkId: value.clientWalkId, startedAt: value.startedAt,
        endedAt: value.endedAt, points: value.points,
      };
    }
    const unsubscribe = subscribeWalk(update);
    async function refresh() {
      try { update(await loadWalkDraft()); }
      catch { if (mountedRef.current) setError('Could not load the current route. Please reopen this screen.'); }
      finally { if (mountedRef.current) setLoadingDraft(false); }
    }
    void refresh();
    const appState = AppState.addEventListener('change', (state) => { if (state === 'active') void refresh(); });
    return () => { mountedRef.current = false; unsubscribe(); appState.remove(); };
  }, []);

  useEffect(() => {
    function tick() {
      if (draft) setElapsed(Math.max(0, Math.floor(((draft.endedAt ? Date.parse(draft.endedAt) : Date.now())
        - Date.parse(draft.startedAt)) / 1000)));
    }
    tick();
    if (phase !== 'tracking') return;
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [phase, draft]);

  async function start() {
    if (phaseRef.current !== 'idle' || loadingDraft) return;
    transition('starting');
    const generation = ++startGenerationRef.current;
    setAllowNavigation(false);
    setError(null);
    setElapsed(0);
    try {
      const clientWalkId = Crypto.randomUUID();
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!mountedRef.current || generation !== startGenerationRef.current) return;
      if (permission.status !== 'granted') throw new Error('Allow location access to record a walk.');
      if (background) {
        if (!await TaskManager.isAvailableAsync() || !await Location.isBackgroundLocationAvailableAsync()) {
          throw new Error('Background recording requires an iOS or Android development build.');
        }
        if (!mountedRef.current || generation !== startGenerationRef.current) return;
        const backgroundPermission = await Location.requestBackgroundPermissionsAsync();
        if (!mountedRef.current || generation !== startGenerationRef.current) return;
        if (backgroundPermission.status !== 'granted') {
          throw new Error('Allow background location in Settings, or turn off background recording.');
        }
      }
      const startedAt = new Date().toISOString();
      const first = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High });
      if (!mountedRef.current || generation !== startGenerationRef.current) return;
      const active = await startWalkTracking(clientWalkId, startedAt, first, background);
      if (!mountedRef.current || generation !== startGenerationRef.current) return;
      setDraft(active);
      transition('tracking');
    } catch (cause) {
      if (!mountedRef.current || generation !== startGenerationRef.current) return;
      transition('idle');
      setError(cause instanceof Error ? cause.message : 'Could not start location tracking.');
    }
  }

  async function save(input: WalkInput) {
    transition('saving');
    setError(null);
    try {
      const walk = await createWalk(input);
      await clearSavedWalk(input.clientWalkId);
      if (!mountedRef.current) return;
      pendingRef.current = null;
      savedWalkIdRef.current = walk.id;
      setAllowNavigation(true);
    } catch (cause) {
      if (mountedRef.current) {
        transition('saveFailed');
        setError(cause instanceof Error ? cause.message : 'Could not save this walk. Try again.');
      }
    }
  }

  async function stop() {
    if (phaseRef.current !== 'tracking' && phaseRef.current !== 'saveFailed') return;
    transition('saving');
    setError(null);
    try {
      const input = await stopWalkTracking();
      pendingRef.current = input;
      await save(input);
    } catch (cause) {
      if (mountedRef.current) {
        transition('saveFailed');
        setError(cause instanceof Error ? cause.message : 'Could not stop this walk. Try again.');
      }
    }
  }

  function confirmDiscard() {
    const keep = () => { blockedNavigationRef.current = null; };
    if (Platform.OS === 'web') {
      if (window.confirm('Discard this unsaved route?')) void discard();
      else keep();
    } else Alert.alert('Discard walk?', 'This unsaved route will be lost.', [
      { text: 'Keep route', style: 'cancel', onPress: keep },
      { text: 'Discard walk', style: 'destructive', onPress: () => void discard() },
    ], { cancelable: false });
  }

  async function discard() {
    if (discardingRef.current || phaseRef.current === 'saving') return;
    discardingRef.current = true;
    startGenerationRef.current++;
    try {
      await discardWalkDraft();
      if (!mountedRef.current) return;
      pendingRef.current = null;
      setDraft(null);
      setElapsed(0);
      setError(null);
      transition('idle');
      if (blockedNavigationRef.current) setAllowNavigation(true);
    } catch {
      if (mountedRef.current) setError('Could not stop recording. Try discarding again.');
      blockedNavigationRef.current = null;
    } finally { discardingRef.current = false; }
  }

  const points = draft?.points ?? [];
  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={24} color={colors.ink} />
        </Pressable>
        <Text style={styles.title}>Walk tracker</Text><View style={{ width: 24 }} />
      </View>
      <WalkMap points={points} followLatest />
      <View style={styles.card}>
        <Text style={styles.time}>{String(Math.floor(elapsed / 60)).padStart(2, '0')}:{String(elapsed % 60).padStart(2, '0')}</Text>
        <Text style={styles.meta}>{points.length} GPS points</Text>
        <Text style={styles.note}>{draft?.background ? 'Recording continues while your phone is locked.'
          : 'Foreground recording: keep WagWag open while walking.'}</Text>
      </View>
      {(error || draft?.error) && <Text style={styles.error}>{error || draft?.error}</Text>}
      {phase === 'idle' && !loadingDraft && <>
        {Platform.OS !== 'web' && <View style={styles.confirmActions}>
          <Text style={styles.meta}>Record with screen locked</Text>
          <Switch value={background} onValueChange={setBackground} />
        </View>}
        {background && <Text style={styles.note}>Background location is used only during your walk. Android may open Settings to ask for permission.</Text>}
        <Pressable style={styles.primary} onPress={() => void start()}><Text style={styles.primaryText}>Start walk</Text></Pressable>
      </>}
      {(loadingDraft || phase === 'starting') && <ActivityIndicator style={styles.busy} color={colors.accent} />}
      {phase === 'tracking' && <Pressable style={styles.primary} onPress={() => void stop()}>
        <Text style={styles.primaryText}>Stop and save</Text>
      </Pressable>}
      {phase === 'saving' && <View style={styles.busy}><ActivityIndicator color={colors.accent} />
        <Text style={styles.meta}>Saving your route...</Text></View>}
      {phase === 'saveFailed' && <Pressable style={styles.primary} onPress={() => {
        if (phaseRef.current !== 'saveFailed') return;
        if (pendingRef.current) void save(pendingRef.current);
        else void stop();
      }}><Text style={styles.primaryText}>Retry save</Text></Pressable>}
      {(phase === 'tracking' || phase === 'saveFailed' || phase === 'starting') && !loadingDraft &&
        <Pressable style={styles.secondary} onPress={confirmDiscard}><Text style={styles.secondaryText}>Discard walk</Text></Pressable>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  card: { backgroundColor: colors.card, borderRadius: 17, padding: 20, marginTop: 18, alignItems: 'center' },
  time: { color: colors.ink, fontWeight: '900', fontSize: 36 },
  meta: { color: colors.muted, marginTop: 6, textAlign: 'center' },
  note: { color: colors.muted, marginTop: 14, textAlign: 'center', lineHeight: 19 },
  error: { color: '#B23725', marginTop: 15, textAlign: 'center', fontWeight: '700' },
  primary: { backgroundColor: colors.accent, minHeight: 54, borderRadius: 16, marginTop: 20,
    alignItems: 'center', justifyContent: 'center' },
  primaryText: { color: 'white', fontWeight: '800', fontSize: 16 },
  secondary: { padding: 16, alignItems: 'center' },
  secondaryText: { color: colors.green, fontWeight: '800' },
  busy: { marginTop: 26, alignItems: 'center' },
  confirmActions: { flexDirection: 'row', justifyContent: 'space-around', marginTop: 20 },
});
