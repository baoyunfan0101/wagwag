import { Ionicons } from '@expo/vector-icons';
import * as Crypto from 'expo-crypto';
import * as Location from 'expo-location';
import { useNavigation, useRouter } from 'expo-router';
import { usePreventRemove } from 'expo-router/react-navigation';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import WalkMap from '@/components/WalkMap';
import { createWalk, type WalkInput, type WalkPoint } from '@/lib/api';
import { colors } from '@/lib/theme';

type Phase = 'idle' | 'starting' | 'tracking' | 'saving' | 'saveFailed';

export default function LiveWalkScreen() {
  const router = useRouter();
  const navigation = useNavigation();
  const [phase, setPhase] = useState<Phase>('idle');
  const [allowNavigation, setAllowNavigation] = useState(false);
  const [points, setPoints] = useState<WalkPoint[]>([]);
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const pointsRef = useRef<WalkPoint[]>([]);
  const clientWalkIdRef = useRef<string | null>(null);
  const startedRef = useRef<string | null>(null);
  const pendingRef = useRef<WalkInput | null>(null);
  const blockedNavigationRef = useRef<(() => void) | null>(null);
  const savedWalkIdRef = useRef<number | null>(null);
  const startGenerationRef = useRef(0);
  const startInFlightRef = useRef(false);
  const subscriptionRef = useRef<Location.LocationSubscription | null>(null);
  const activeRef = useRef(false);
  const mountedRef = useRef(true);
  const savingRef = useRef(false);

  usePreventRemove(phase !== 'idle' && !allowNavigation, ({ data }) => {
    if (phase === 'saving') {
      if (Platform.OS !== 'web') Alert.alert('Saving walk', 'Please wait for the save to finish.');
      return;
    }
    if (blockedNavigationRef.current) return;
    blockedNavigationRef.current = () => navigation.dispatch(data.action);
    if (Platform.OS === 'web') {
      if (window.confirm('Discard this unsaved route?')) discard();
      else blockedNavigationRef.current = null;
      return;
    }
    Alert.alert('Discard walk?', 'This unsaved route will be lost.', [
      { text: 'Keep route', style: 'cancel', onPress: () => { blockedNavigationRef.current = null; } },
      { text: 'Discard walk', style: 'destructive', onPress: discard },
    ], { cancelable: false });
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
    return () => {
      mountedRef.current = false;
      activeRef.current = false;
      subscriptionRef.current?.remove();
      subscriptionRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (phase !== 'tracking') return;
    const timer = setInterval(() => {
      if (startedRef.current) setElapsed(Math.floor((Date.now() - Date.parse(startedRef.current)) / 1000));
    }, 1000);
    return () => clearInterval(timer);
  }, [phase]);

  function addLocation(location: Location.LocationObject) {
    if (!activeRef.current || !Number.isFinite(location.coords.latitude)
        || !Number.isFinite(location.coords.longitude)) return;
    const previous = pointsRef.current.at(-1);
    if (previous && previous.latitude === location.coords.latitude
        && previous.longitude === location.coords.longitude) return;
    if (pointsRef.current.length >= 2000) {
      setError('Route limit reached. Stop and save this walk.');
      return;
    }
    const point = { latitude: location.coords.latitude, longitude: location.coords.longitude,
      recordedAt: new Date().toISOString() };
    pointsRef.current = [...pointsRef.current, point];
    setPoints(pointsRef.current);
  }

  async function start() {
    if (phase !== 'idle' || startInFlightRef.current) return;
    startInFlightRef.current = true;
    const generation = ++startGenerationRef.current;
    setAllowNavigation(false);
    setElapsed(0);
    setPoints([]);
    setPhase('starting');
    setError(null);
    setConfirmDiscard(false);
    try {
      clientWalkIdRef.current = Crypto.randomUUID();
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!mountedRef.current || generation !== startGenerationRef.current) return;
      if (permission.status !== 'granted') throw new Error('Allow location access to record a walk.');
      const startedAt = new Date().toISOString();
      const first = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High });
      if (!mountedRef.current || generation !== startGenerationRef.current) return;
      startedRef.current = startedAt;
      pointsRef.current = [];
      activeRef.current = true;
      addLocation(first);
      const subscription = await Location.watchPositionAsync(
        { accuracy: Location.Accuracy.High, distanceInterval: 5, timeInterval: 3000 },
        addLocation,
        (message) => { if (mountedRef.current && generation === startGenerationRef.current) setError(message); },
      );
      if (!mountedRef.current || generation !== startGenerationRef.current || !activeRef.current) {
        subscription.remove(); return;
      }
      subscriptionRef.current = subscription;
      setPhase('tracking');
    } catch (cause) {
      if (!mountedRef.current || generation !== startGenerationRef.current) return;
      activeRef.current = false;
      subscriptionRef.current?.remove();
      subscriptionRef.current = null;
      startedRef.current = null;
      clientWalkIdRef.current = null;
      pointsRef.current = [];
      if (mountedRef.current) {
        setPoints([]);
        setPhase('idle');
        setError(cause instanceof Error ? cause.message : 'Could not start location tracking.');
      }
    } finally {
      if (generation === startGenerationRef.current) startInFlightRef.current = false;
    }
  }

  async function save(input: WalkInput) {
    if (savingRef.current) return;
    savingRef.current = true;
    setPhase('saving');
    setError(null);
    try {
      const walk = await createWalk(input);
      if (!mountedRef.current) return;
      pendingRef.current = null;
      clientWalkIdRef.current = null;
      savedWalkIdRef.current = walk.id;
      setAllowNavigation(true);
    } catch (cause) {
      if (mountedRef.current) {
        setPhase('saveFailed');
        setError(cause instanceof Error ? cause.message : 'Could not save this walk. Try again.');
      }
    } finally { savingRef.current = false; }
  }

  function stop() {
    if (phase !== 'tracking') return;
    activeRef.current = false;
    subscriptionRef.current?.remove();
    subscriptionRef.current = null;
    if (!clientWalkIdRef.current || !startedRef.current || pointsRef.current.length === 0) {
      clientWalkIdRef.current = null;
      startedRef.current = null;
      setPhase('idle');
      setError('No GPS points were recorded. Please try again.');
      return;
    }
    const input = { clientWalkId: clientWalkIdRef.current,
      startedAt: startedRef.current, endedAt: new Date().toISOString(),
      points: pointsRef.current };
    pendingRef.current = input;
    void save(input);
  }

  function discard() {
    startGenerationRef.current++;
    startInFlightRef.current = false;
    activeRef.current = false;
    subscriptionRef.current?.remove();
    subscriptionRef.current = null;
    startedRef.current = null;
    clientWalkIdRef.current = null;
    pendingRef.current = null;
    pointsRef.current = [];
    setPoints([]);
    setElapsed(0);
    setError(null);
    setConfirmDiscard(false);
    setPhase('idle');
    if (blockedNavigationRef.current) setAllowNavigation(true);
  }

  const minutes = Math.floor(elapsed / 60);
  const seconds = elapsed % 60;

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
        <Text style={styles.time}>{String(minutes).padStart(2, '0')}:{String(seconds).padStart(2, '0')}</Text>
        <Text style={styles.meta}>{points.length} GPS points</Text>
        <Text style={styles.note}>Keep the app open while walking. Background tracking comes later.</Text>
      </View>
      {error && <Text style={styles.error}>{error}</Text>}
      {phase === 'idle' && <Pressable style={styles.primary} onPress={() => void start()}>
        <Text style={styles.primaryText}>Start walk</Text>
      </Pressable>}
      {phase === 'starting' && <ActivityIndicator style={styles.busy} color={colors.accent} />}
      {phase === 'tracking' && <Pressable style={styles.primary} onPress={stop}>
        <Text style={styles.primaryText}>Stop and save</Text>
      </Pressable>}
      {phase === 'saving' && <View style={styles.busy}><ActivityIndicator color={colors.accent} />
        <Text style={styles.meta}>Saving your route...</Text></View>}
      {phase === 'saveFailed' && <Pressable style={styles.primary}
        onPress={() => { if (pendingRef.current) void save(pendingRef.current); }}>
        <Text style={styles.primaryText}>Retry save</Text>
      </Pressable>}
      {(phase === 'tracking' || phase === 'saveFailed') && !confirmDiscard &&
        <Pressable style={styles.secondary} onPress={() => setConfirmDiscard(true)}>
          <Text style={styles.secondaryText}>Discard walk</Text>
        </Pressable>}
      {confirmDiscard && <View style={styles.confirm}>
        <Text style={styles.meta}>Discard this unsaved route?</Text>
        <View style={styles.confirmActions}>
          <Pressable onPress={() => setConfirmDiscard(false)}><Text style={styles.secondaryText}>Keep it</Text></Pressable>
          <Pressable onPress={discard}><Text style={styles.error}>Discard</Text></Pressable>
        </View>
      </View>}
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
  confirm: { backgroundColor: colors.card, borderRadius: 14, padding: 18, marginTop: 10 },
  confirmActions: { flexDirection: 'row', justifyContent: 'space-around', marginTop: 20 },
});
