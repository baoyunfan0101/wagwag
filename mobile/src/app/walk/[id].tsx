import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import WalkMap from '@/components/WalkMap';
import { ApiError, claimWalkTerritory, getWalk, getWalkTerritory, type Territory, type Walk } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function WalkDetailScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const [walk, setWalk] = useState<Walk | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [territory, setTerritory] = useState<Territory | null>(null);
  const [territoryLoading, setTerritoryLoading] = useState(true);
  const [territoryError, setTerritoryError] = useState<string | null>(null);
  const [claimError, setClaimError] = useState<string | null>(null);
  const [claiming, setClaiming] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setTerritoryLoading(true);
    setTerritoryError(null);
    setClaimError(null);
    setTerritory(null);
    getWalk(Number(id)).then((result) => {
      if (active) { setWalk(result); setError(null); }
    }).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Could not load this walk.');
    }).finally(() => { if (active) setLoading(false); });
    getWalkTerritory(Number(id)).then((result) => {
      if (active) { setTerritory(result); setTerritoryError(null); }
    }).catch((cause) => {
      if (active && !(cause instanceof ApiError && cause.status === 404)) {
        setTerritoryError('Could not load this walk territory.');
      }
    }).finally(() => { if (active) setTerritoryLoading(false); });
    return () => { active = false; };
  }, [id]);

  async function claim() {
    if (claiming || !walk) return;
    setClaiming(true);
    setClaimError(null);
    try {
      setTerritory(await claimWalkTerritory(walk.id));
    } catch {
      setClaimError('Could not claim territory. Please try again.');
    } finally {
      setClaiming(false);
    }
  }

  async function reloadTerritory() {
    if (!walk) return;
    setTerritoryLoading(true);
    setTerritoryError(null);
    try {
      setTerritory(await getWalkTerritory(walk.id));
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.status === 404)) {
        setTerritoryError('Could not load this walk territory.');
      }
    } finally {
      setTerritoryLoading(false);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={24} color={colors.ink} />
        </Pressable>
        <Text style={styles.title}>Walk route</Text><View style={{ width: 24 }} />
      </View>
      {loading ? <ActivityIndicator color={colors.accent} /> : error ?
        <Text style={styles.error}>{error}</Text> : walk ? <>
          <WalkMap points={walk.points} route={walk.route} territory={territory?.area} />
          <View style={styles.card}>
            <Text style={styles.date}>{new Date(walk.startedAt).toLocaleString()}</Text>
            <Text style={styles.meta}>Ended {new Date(walk.endedAt).toLocaleTimeString()}</Text>
            <Text style={styles.meta}>{walk.points.length} GPS points saved</Text>
            <Text style={styles.meta}>{(walk.distanceMeters / 1000).toFixed(2)} km walked</Text>
            {territory && <Text style={styles.meta}>Territory claimed: {Math.round(territory.areaSquareMeters)} sq m</Text>}
          </View>
          {territoryLoading ? <ActivityIndicator style={styles.action} color={colors.accent} /> :
            territoryError ? <>
              <Text style={styles.error}>{territoryError}</Text>
              <Pressable style={styles.action} onPress={reloadTerritory}><Text style={styles.actionText}>Try again</Text></Pressable>
            </> : !territory &&
              <>
                {claimError && <Text style={styles.error}>{claimError}</Text>}
                <Pressable style={styles.action} disabled={claiming} onPress={claim}>
                  <Text style={styles.actionText}>{claiming ? 'Claiming territory...' : 'Claim territory from this walk'}</Text>
                </Pressable>
              </>}
        </> : null}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  card: { backgroundColor: colors.card, borderRadius: 17, padding: 20, marginTop: 18 },
  date: { color: colors.ink, fontSize: 18, fontWeight: '800' },
  meta: { color: colors.muted, marginTop: 8 },
  error: { color: '#B23725' },
  action: { marginTop: 18, padding: 16, borderRadius: 14, backgroundColor: colors.green,
    alignItems: 'center' },
  actionText: { color: '#FFFFFF', fontWeight: '800', textAlign: 'center' },
});
