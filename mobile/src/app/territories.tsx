import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getTerritoryHistory, getTerritoryLeaderboard, type TerritoryLeader,
  type TerritorySummary } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function TerritoriesScreen() {
  const router = useRouter();
  const [leaders, setLeaders] = useState<TerritoryLeader[]>([]);
  const [history, setHistory] = useState<TerritorySummary[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const moreInFlight = useRef(false);

  const loadFirst = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    setNextPage(null);
    setMoreError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setHistory([]); }
    try {
      const [rankings, page] = await Promise.all([getTerritoryLeaderboard(), getTerritoryHistory()]);
      if (current !== generation.current) return;
      setLeaders(rankings);
      setHistory(page.items);
      setNextPage(page.nextPage);
      setError(null);
    } catch (cause) {
      if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load territories.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, []);

  useFocusEffect(useCallback(() => { void loadFirst(); }, [loadFirst]));

  async function loadMore(retry = false) {
    if (nextPage === null || moreInFlight.current || loading || refreshing || (moreError && !retry)) return;
    moreInFlight.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await getTerritoryHistory(20, nextPage);
      if (current !== generation.current) return;
      setHistory(existing => {
        const ids = new Set(existing.map(item => item.id));
        return [...existing, ...page.items.filter(item => !ids.has(item.id))];
      });
      setNextPage(page.nextPage);
    } catch (cause) {
      if (current === generation.current) setMoreError(cause instanceof Error ? cause.message : 'Could not load more claims.');
    } finally { moreInFlight.current = false; setLoadingMore(false); }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={history} keyExtractor={item => String(item.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void loadFirst(true)}
      onEndReached={() => void loadMore()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={24} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Territories</Text><View style={{ width: 24 }} />
        </View>
        <Text style={styles.note}>Overlapping ground belongs to the strongest current claim. Strength fades each week.</Text>
        {loading && <ActivityIndicator color={colors.accent} />}
        {error && <Pressable onPress={() => void loadFirst()}><Text style={styles.error}>{error} Try again</Text></Pressable>}
        {!loading && !error && <>
          <Text style={styles.section}>Leaderboard</Text>
          {leaders.length === 0 ? <Text style={styles.note}>No territory has been claimed yet.</Text> :
            leaders.map((leader, index) => <View key={leader.petId} style={styles.rank}>
              <Text style={styles.rankName}>{index + 1}. {leader.petName}</Text>
              <Text style={styles.note}>{Math.round(leader.areaSquareMeters)} sq m | {leader.claimCount} claims</Text>
            </View>)}
          <Text style={styles.section}>My claim history</Text>
        </>}
      </>}
      renderItem={({ item }) => <Pressable style={styles.card}
        onPress={() => router.push({ pathname: '/walk/[id]', params: { id: String(item.walkId) } })}>
        <Text style={styles.rankName}>{new Date(item.createdAt).toLocaleString()}</Text>
        <Text style={styles.note}>{Math.round(item.ownedAreaSquareMeters)} / {Math.round(item.areaSquareMeters)} sq m controlled</Text>
        <Text style={styles.note}>Strength {item.effectiveStrength} / {item.baseStrength}</Text>
      </Pressable>}
      ListEmptyComponent={!loading && !error ? <Text style={styles.note}>No claims yet. Open a saved walk to claim one.</Text> : null}
      ListFooterComponent={loadingMore ? <ActivityIndicator color={colors.accent} /> : moreError ?
        <Pressable onPress={() => void loadMore(true)}><Text style={styles.error}>{moreError} Try again</Text></Pressable> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 },
  title: { color: colors.ink, fontSize: 23, fontWeight: '900' },
  section: { color: colors.ink, fontSize: 20, fontWeight: '800', marginTop: 24, marginBottom: 12 },
  rank: { backgroundColor: colors.card, borderRadius: 13, padding: 14, marginBottom: 8 },
  card: { backgroundColor: colors.card, borderRadius: 16, padding: 16, marginBottom: 10 },
  rankName: { color: colors.ink, fontWeight: '800' },
  note: { color: colors.muted, marginTop: 5, lineHeight: 20 },
  error: { color: '#B23725', marginVertical: 12 },
});
