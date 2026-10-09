import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { ListingCard } from '@/components/ListingCard';
import { getListings, getRecommendedListings, type Listing, type ListingScope } from '@/lib/api';
import { colors } from '@/lib/theme';

const tabs: { scope: ListingScope; label: string }[] = [
  { scope: 'available', label: 'Browse' }, { scope: 'favorites', label: 'Saved' }, { scope: 'mine', label: 'My listings' },
];

export default function MarketplaceScreen() {
  const router = useRouter();
  const [searchText, setSearchText] = useState('');
  const [query, setQuery] = useState('');
  const [recommended, setRecommended] = useState<Listing[]>([]);
  const [scope, setScope] = useState<ListingScope>('available');
  const [items, setItems] = useState<Listing[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const morePending = useRef(false);
  const cursorRef = useRef<string | null>(null);

  const reload = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    morePending.current = false;
    cursorRef.current = null;
    setCursor(null);
    setItems([]);
    setMoreError(null);
    setLoadingMore(false);
    setRecommended([]);
    if (refresh) setRefreshing(true);
    else setLoading(true);
    try {
      const [page, suggestions] = await Promise.all([getListings(scope, 20, undefined, query),
        scope === 'available' && !query ? getRecommendedListings().catch(() => []) : Promise.resolve([])]);
      if (generation.current !== current) return;
      setItems(page.items);
      setRecommended(suggestions);
      setCursor(page.nextCursor);
      cursorRef.current = page.nextCursor;
      setError(null);
    } catch (cause) {
      if (generation.current === current) setError(cause instanceof Error ? cause.message : 'Could not load listings.');
    } finally {
      if (generation.current === current) { setLoading(false); setRefreshing(false); }
    }
  }, [scope, query]);

  useFocusEffect(useCallback(() => {
    void reload();
    return () => { generation.current++; morePending.current = false; };
  }, [reload]));

  async function more(retry = false) {
    const next = cursorRef.current;
    if (!next || morePending.current || loading || refreshing || (moreError && !retry)) return;
    morePending.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await getListings(scope, 20, next, query);
      if (generation.current !== current) return;
      setItems(previous => {
        const known = new Set(previous.map(item => item.id));
        return [...previous, ...page.items.filter(item => !known.has(item.id))];
      });
      setCursor(page.nextCursor);
      cursorRef.current = page.nextCursor;
    } catch (cause) {
      if (generation.current === current) setMoreError(cause instanceof Error ? cause.message : 'Could not load more listings.');
    } finally {
      if (generation.current === current) { morePending.current = false; setLoadingMore(false); }
    }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList contentContainerStyle={styles.content} data={items} keyExtractor={item => String(item.id)}
      renderItem={({ item }) => <ListingCard listing={item} onPress={() => router.push({ pathname: '/listing/[id]', params: { id: String(item.id) } })} />}
      onEndReached={() => void more()} onEndReachedThreshold={0.4}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void reload(true)} tintColor={colors.accent} />}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} accessibilityLabel="Go back"><Ionicons name="arrow-back" size={24} color={colors.ink} /></Pressable>
          <Text style={styles.heading}>Second-hand shop</Text>
          <Pressable onPress={() => router.push('/listing/new')} accessibilityLabel="Create listing"><Ionicons name="add-circle" size={29} color={colors.accent} /></Pressable>
        </View>
        <Text style={styles.intro}>Give useful pet gear another adventure.</Text>
        <View style={{ flexDirection: 'row', gap: 14, marginBottom: 14 }}>
          <Pressable onPress={() => router.push('/listing/nearby')}><Text style={styles.tabText}>Near me</Text></Pressable>
          <Pressable onPress={() => router.push('/listing-orders')}><Text style={styles.tabText}>My orders</Text></Pressable>
        </View>
        <View style={{ flexDirection: 'row', gap: 8, marginBottom: 14 }}>
          <TextInput value={searchText} onChangeText={setSearchText} placeholder="Search pet gear" returnKeyType="search"
            style={{ flex: 1, backgroundColor: colors.card, padding: 12, borderRadius: 12, color: colors.ink }}
            onSubmitEditing={() => setQuery(searchText.trim())} />
          <Pressable style={styles.tab} onPress={() => setQuery(searchText.trim())}><Text style={styles.tabText}>Search</Text></Pressable>
        </View>
        {query && <Pressable onPress={() => { setSearchText(''); setQuery(''); }}><Text style={styles.intro}>Clear search: {query}</Text></Pressable>}
        {recommended.length > 0 && <>
          <Text style={[styles.tabText, { marginBottom: 10 }]}>Recommended from popular favorites</Text>
          <ScrollView horizontal contentContainerStyle={{ gap: 10 }} style={{ marginBottom: 12 }}>
            {recommended.map(item => <View key={item.id} style={{ width: 290 }}>
              <ListingCard listing={item} onPress={() => router.push({ pathname: '/listing/[id]', params: { id: String(item.id) } })} />
            </View>)}
          </ScrollView>
        </>}
        <View style={styles.tabs}>{tabs.map(tab => <Pressable key={tab.scope} onPress={() => setScope(tab.scope)}
          style={[styles.tab, scope === tab.scope && styles.selected]} accessibilityLabel={`${tab.label} listings`}>
          <Text style={scope === tab.scope ? styles.selectedText : styles.tabText}>{tab.label}</Text>
        </Pressable>)}</View>
        {error && <Pressable onPress={() => void reload()}><Text style={styles.error}>{error} Tap to retry.</Text></Pressable>}
        {loading && <ActivityIndicator color={colors.accent} style={styles.spinner} />}
      </>}
      ListEmptyComponent={!loading && !error ? <Text style={styles.empty}>No listings here yet.</Text> : null}
      ListFooterComponent={loadingMore ? <ActivityIndicator color={colors.accent} style={styles.spinner} />
        : moreError ? <Pressable onPress={() => void more(true)}><Text style={styles.error}>{moreError} Tap to retry.</Text></Pressable>
          : items.length > 0 && !cursor ? <Text style={styles.end}>You are all caught up.</Text> : null} />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 20, paddingBottom: 40 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginVertical: 10 },
  heading: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  intro: { color: colors.muted, marginBottom: 18, fontSize: 14 },
  tabs: { flexDirection: 'row', gap: 8, marginBottom: 20 },
  tab: { flex: 1, borderRadius: 12, paddingVertical: 12, backgroundColor: colors.card, alignItems: 'center' },
  selected: { backgroundColor: colors.green },
  tabText: { color: colors.green, fontWeight: '800' },
  selectedText: { color: 'white', fontWeight: '800' },
  error: { color: '#B23725', marginBottom: 12, lineHeight: 20 },
  empty: { color: colors.muted, textAlign: 'center', marginTop: 60 },
  end: { color: colors.muted, textAlign: 'center', marginTop: 16 },
  spinner: { marginVertical: 22 },
});
