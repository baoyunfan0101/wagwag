import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getWalks, type WalkSummary } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function WalksScreen() {
  const router = useRouter();
  const [items, setItems] = useState<WalkSummary[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const nextInFlight = useRef(false);

  const loadFirst = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    setNextPage(null);
    setLoadMoreError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setItems([]); }
    try {
      const page = await getWalks();
      if (current !== generation.current) return;
      setItems(page.items);
      setNextPage(page.nextPage);
      setError(null);
    } catch (cause) {
      if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load walks.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, []);

  useFocusEffect(useCallback(() => { void loadFirst(); }, [loadFirst]));

  async function loadMore(retry = false) {
    if (nextPage === null || nextInFlight.current || loading || refreshing || (loadMoreError && !retry)) return;
    nextInFlight.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const page = await getWalks(20, nextPage);
      if (current !== generation.current) return;
      setItems((existing) => {
        const ids = new Set(existing.map((item) => item.id));
        return [...existing, ...page.items.filter((item) => !ids.has(item.id))];
      });
      setNextPage(page.nextPage);
    } catch (cause) {
      if (current === generation.current) setLoadMoreError(cause instanceof Error ? cause.message : 'Could not load more walks.');
    } finally { nextInFlight.current = false; setLoadingMore(false); }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={items} keyExtractor={(item) => String(item.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void loadFirst(true)}
      onEndReached={() => void loadMore()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={24} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Walks</Text><View style={{ width: 24 }} />
        </View>
        <Text style={styles.subtitle}>Keep a route for every adventure together.</Text>
        <Pressable style={styles.start} onPress={() => router.push('/walk/live')}>
          <Ionicons name="walk" size={21} color="white" /><Text style={styles.startText}>Start a walk</Text>
        </Pressable>
        <Pressable style={styles.footer} onPress={() => router.push('/walk/nearby')}>
          <Text style={styles.date}>My routes near me</Text>
        </Pressable>
        <Text style={styles.section}>Past walks</Text>
        {error && <Pressable onPress={() => void loadFirst()}><Text style={styles.error}>{error}  Try again</Text></Pressable>}
      </>}
      renderItem={({ item }) => <Pressable style={styles.card}
        onPress={() => router.push({ pathname: '/walk/[id]', params: { id: String(item.id) } })}>
        <View style={styles.cardIcon}><Ionicons name="footsteps" size={20} color={colors.green} /></View>
        <View style={styles.cardText}>
          <Text style={styles.date}>{new Date(item.startedAt).toLocaleString()}</Text>
          <Text style={styles.meta}>{(item.distanceMeters / 1000).toFixed(2)} km | {item.pointCount} GPS points</Text>
        </View>
        <Ionicons name="chevron-forward" size={18} color={colors.muted} />
      </Pressable>}
      ListEmptyComponent={loading ? <ActivityIndicator style={styles.empty} color={colors.accent} />
        : !error ? <Text style={styles.empty}>No walks yet. Start your first one.</Text> : null}
      ListFooterComponent={items.length > 0 ? <View style={styles.footer}>
        {loadingMore ? <ActivityIndicator color={colors.accent} /> : loadMoreError ?
          <Pressable onPress={() => void loadMore(true)}><Text style={styles.error}>{loadMoreError}  Try again</Text></Pressable>
          : nextPage === null ? <Text style={styles.meta}>All walks loaded.</Text> : null}
      </View> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 },
  title: { color: colors.ink, fontSize: 23, fontWeight: '900' },
  subtitle: { color: colors.muted, marginBottom: 20 },
  start: { backgroundColor: colors.accent, borderRadius: 16, minHeight: 54,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 9 },
  startText: { color: 'white', fontWeight: '800', fontSize: 16 },
  section: { color: colors.ink, fontWeight: '800', fontSize: 20, marginTop: 30, marginBottom: 14 },
  card: { backgroundColor: colors.card, borderRadius: 16, padding: 16, marginBottom: 11,
    flexDirection: 'row', alignItems: 'center', gap: 13 },
  cardIcon: { width: 43, height: 43, borderRadius: 13, backgroundColor: colors.greenPale,
    alignItems: 'center', justifyContent: 'center' },
  cardText: { flex: 1 },
  date: { color: colors.ink, fontWeight: '800' },
  meta: { color: colors.muted, fontSize: 12, marginTop: 4 },
  empty: { minHeight: 180, textAlign: 'center', textAlignVertical: 'center', color: colors.muted },
  footer: { minHeight: 50, alignItems: 'center' },
  error: { color: '#B23725', marginBottom: 12 },
});
