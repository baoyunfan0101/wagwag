import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { DEV_PET_ID, getListingOrders, type ListingOrder } from '@/lib/api';
import { formatListingPrice } from '@/lib/listingPrice';
import { colors } from '@/lib/theme';

export default function ListingOrdersScreen() {
  const router = useRouter();
  const [items, setItems] = useState<ListingOrder[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [moreLoading, setMoreLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const morePending = useRef(false);
  const pageRef = useRef<number | null>(null);

  const first = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    pageRef.current = null;
    morePending.current = false;
    setMoreLoading(false);
    setNextPage(null);
    setMoreError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setItems([]); }
    try {
      const page = await getListingOrders();
      if (generation.current !== current) return;
      setItems(page.items); setNextPage(page.nextPage); pageRef.current = page.nextPage; setError(null);
    } catch (cause) { if (generation.current === current) setError(cause instanceof Error ? cause.message : 'Could not load orders.'); }
    finally { if (generation.current === current) { setLoading(false); setRefreshing(false); } }
  }, []);
  useFocusEffect(useCallback(() => { void first(); return () => { generation.current++; }; }, [first]));

  async function more(retry = false) {
    const next = pageRef.current;
    if (next === null || morePending.current || loading || refreshing || (moreError && !retry)) return;
    const current = generation.current;
    morePending.current = true; setMoreLoading(true); setMoreError(null);
    try {
      const page = await getListingOrders(next);
      if (generation.current !== current) return;
      setItems(previous => {
        const ids = new Set(previous.map(item => item.id));
        return [...previous, ...page.items.filter(item => !ids.has(item.id))];
      });
      setNextPage(page.nextPage); pageRef.current = page.nextPage;
    } catch (cause) { if (generation.current === current) setMoreError(cause instanceof Error ? cause.message : 'Could not load more orders.'); }
    finally { if (generation.current === current) { morePending.current = false; setMoreLoading(false); } }
  }

  return <SafeAreaView style={styles.safe}><FlatList data={items} keyExtractor={item => String(item.id)}
    contentContainerStyle={styles.content} refreshing={refreshing} onRefresh={() => void first(true)}
    onEndReached={() => void more()} onEndReachedThreshold={0.4}
    ListHeaderComponent={<>
      <View style={styles.header}><Pressable onPress={() => router.back()} accessibilityLabel="Go back">
        <Ionicons name="arrow-back" size={24} color={colors.ink} /></Pressable><Text style={styles.title}>My orders</Text><View style={{ width: 24 }} /></View>
      <Text style={styles.note}>Arrange pickup in messages. The seller confirms when the handoff is complete.</Text>
      {error && <Pressable onPress={() => void first()}><Text style={styles.error}>{error} Retry</Text></Pressable>}
    </>}
    renderItem={({ item }) => <Pressable style={styles.card} onPress={() => router.push({ pathname: '/listing-order/[id]', params: { id: String(item.id) } })}>
      <Text style={styles.item}>{item.title}</Text><Text style={styles.note}>{formatListingPrice(item.priceCents)} | {item.status}</Text>
      <Text style={styles.note}>{item.sellerPetId === DEV_PET_ID ? `Buyer: ${item.buyerName}` : `Seller: ${item.sellerName}`}</Text>
    </Pressable>}
    ListEmptyComponent={loading ? <ActivityIndicator color={colors.accent} /> : !error ? <Text style={styles.note}>No orders yet.</Text> : null}
    ListFooterComponent={moreLoading ? <ActivityIndicator color={colors.accent} /> : moreError ?
      <Pressable onPress={() => void more(true)}><Text style={styles.error}>{moreError} Retry</Text></Pressable> :
      items.length > 0 && nextPage === null ? <Text style={styles.note}>All orders loaded.</Text> : null} />
  </SafeAreaView>;
}
const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background }, content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 24 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 18 },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' }, item: { color: colors.ink, fontSize: 18, fontWeight: '800' },
  card: { backgroundColor: colors.card, borderRadius: 16, padding: 18, marginTop: 12 },
  note: { color: colors.muted, lineHeight: 22, marginVertical: 5 }, error: { color: '#B23725', marginVertical: 12 },
});
