import { Ionicons } from '@expo/vector-icons';
import * as Location from 'expo-location';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getNearbyTasks, type NearbyTask } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function NearbyTasksScreen() {
  const router = useRouter();
  const [items, setItems] = useState<NearbyTask[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const centerRef = useRef<{ latitude: number; longitude: number } | null>(null);
  const generationRef = useRef(0);
  const moreInFlight = useRef(false);

  const first = useCallback(async (refresh = false) => {
    const generation = ++generationRef.current;
    setNextPage(null);
    setMoreError(null);
    setError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setItems([]); }
    try {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') throw new Error('Allow location access to find nearby tasks.');
      const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      const center = { latitude: position.coords.latitude, longitude: position.coords.longitude };
      const page = await getNearbyTasks(center.latitude, center.longitude);
      if (generation !== generationRef.current) return;
      centerRef.current = center;
      setItems(page.items);
      setNextPage(page.nextPage);
    } catch (cause) {
      if (generation === generationRef.current) setError(cause instanceof Error ? cause.message : 'Could not load nearby tasks.');
    } finally {
      if (generation === generationRef.current) { setLoading(false); setRefreshing(false); }
    }
  }, []);

  useFocusEffect(useCallback(() => {
    void first();
    return () => { generationRef.current++; };
  }, [first]));

  async function more(retry = false) {
    const center = centerRef.current;
    if (!center || nextPage === null || moreInFlight.current || loading || refreshing || (moreError && !retry)) return;
    moreInFlight.current = true;
    const generation = generationRef.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await getNearbyTasks(center.latitude, center.longitude, nextPage);
      if (generation !== generationRef.current) return;
      setItems(existing => {
        const ids = new Set(existing.map(item => item.task.id));
        return [...existing, ...page.items.filter(item => !ids.has(item.task.id))];
      });
      setNextPage(page.nextPage);
    } catch (cause) {
      if (generation === generationRef.current) setMoreError(cause instanceof Error ? cause.message : 'Could not load more tasks.');
    } finally { moreInFlight.current = false; setLoadingMore(false); }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={items} keyExtractor={item => String(item.task.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void first(true)} onEndReached={() => void more()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={24} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Tasks near me</Text><View style={{ width: 24 }} />
        </View>
        <Text style={styles.note}>Open tasks within 5 km, nearest first. Public distances use approximate task locations.</Text>
        {error && <Pressable onPress={() => void first()}><Text style={styles.error}>{error} Try again</Text></Pressable>}
      </>}
      renderItem={({ item }) => <Pressable style={styles.card}
        onPress={() => router.push({ pathname: '/task/[id]', params: { id: String(item.task.id) } })}>
        <Text style={styles.cardTitle}>{item.task.title}</Text>
        <Text style={styles.note}>{item.task.locationExact ? '' : 'Approx. '}{(item.distanceMeters / 1000).toFixed(1)} km away</Text>
        <Text style={styles.note}>{item.task.category.replaceAll('_', ' ')} | {item.task.creatorName}</Text>
      </Pressable>}
      ListEmptyComponent={loading ? <ActivityIndicator color={colors.accent} /> : !error ?
        <Text style={styles.note}>No available tasks nearby yet.</Text> : null}
      ListFooterComponent={loadingMore ? <ActivityIndicator color={colors.accent} /> : moreError ?
        <Pressable onPress={() => void more(true)}><Text style={styles.error}>{moreError} Try again</Text></Pressable> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  card: { backgroundColor: colors.card, borderRadius: 16, padding: 18, marginBottom: 12 },
  cardTitle: { color: colors.ink, fontWeight: '800', fontSize: 17 },
  note: { color: colors.muted, marginVertical: 8, lineHeight: 20 },
  error: { color: '#B23725', marginVertical: 15 },
});
