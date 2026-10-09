import { Ionicons } from '@expo/vector-icons';
import * as Location from 'expo-location';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getNearbyListings, type Listing } from '@/lib/api';
import { ListingCard } from '@/components/ListingCard';
import { colors } from '@/lib/theme';

export default function NearbyListingsScreen() {
  const router = useRouter();
  const [items, setItems] = useState<Listing[]>([]);
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
    moreInFlight.current = false;
    setLoadingMore(false);
    setNextPage(null);
    setMoreError(null);
    setError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setItems([]); }
    try {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') throw new Error('Allow location access to find nearby items.');
      const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      const center = { latitude: position.coords.latitude, longitude: position.coords.longitude };
      const page = await getNearbyListings(center.latitude, center.longitude);
      if (generation !== generationRef.current) return;
      centerRef.current = center;
      setItems(page.items);
      setNextPage(page.nextPage);
    } catch (cause) {
      if (generation === generationRef.current) setError(cause instanceof Error ? cause.message : 'Could not load nearby items.');
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
      const page = await getNearbyListings(center.latitude, center.longitude, nextPage);
      if (generation !== generationRef.current) return;
      setItems(existing => {
        const ids = new Set(existing.map(item => item.id));
        return [...existing, ...page.items.filter(item => !ids.has(item.id))];
      });
      setNextPage(page.nextPage);
    } catch (cause) {
      if (generation === generationRef.current) setMoreError(cause instanceof Error ? cause.message : 'Could not load more items.');
    } finally {
      if (generation === generationRef.current) { moreInFlight.current = false; setLoadingMore(false); }
    }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={items} keyExtractor={item => String(item.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void first(true)} onEndReached={() => void more()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={24} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Items near me</Text><View style={{ width: 24 }} />
        </View>
        <Text style={styles.note}>Available items within 5 km of your location, nearest first. Item locations are rounded to a neighborhood.</Text>
        {error && <Pressable onPress={() => void first()}><Text style={styles.error}>{error} Try again</Text></Pressable>}
      </>}
      renderItem={({ item }) => <ListingCard listing={item}
        onPress={() => router.push({ pathname: '/listing/[id]', params: { id: String(item.id) } })} />}
      ListEmptyComponent={loading ? <ActivityIndicator color={colors.accent} /> : !error ?
        <Text style={styles.note}>No available items nearby yet.</Text> : null}
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
