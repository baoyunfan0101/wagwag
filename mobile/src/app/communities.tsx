import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getCommunities, type Community } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function CommunitiesScreen() {
  const router = useRouter();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<Community[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const loadingNext = useRef(false);
  const lastLoadedPage = useRef<number | null>(null);

  const loadFirst = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    lastLoadedPage.current = null;
    setNextPage(null);
    setLoadMoreError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setItems([]); }
    try {
      const page = await getCommunities(query);
      if (current !== generation.current) return;
      setItems(page.items);
      setNextPage(page.nextPage);
      setError(null);
    } catch (cause) {
      if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load communities.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, [query]);

  useFocusEffect(useCallback(() => { void loadFirst(); }, [loadFirst]));

  async function loadMore(retry = false) {
    if (nextPage === null || nextPage === lastLoadedPage.current || loading || refreshing
        || loadingNext.current || (loadMoreError && !retry)) return;
    loadingNext.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const page = await getCommunities(query, 20, nextPage);
      if (current !== generation.current) return;
      lastLoadedPage.current = nextPage;
      setItems((existing) => {
        const ids = new Set(existing.map((item) => item.id));
        return [...existing, ...page.items.filter((item) => !ids.has(item.id))];
      });
      setNextPage(page.nextPage);
    } catch (cause) {
      if (current === generation.current) setLoadMoreError(cause instanceof Error ? cause.message : 'Could not load more communities.');
    } finally {
      loadingNext.current = false;
      setLoadingMore(false);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={items} keyExtractor={(item) => String(item.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void loadFirst(true)}
      onEndReached={() => void loadMore()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={22} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Communities</Text>
          <Pressable style={styles.create} onPress={() => router.push('/community/new')} accessibilityLabel="Create community">
            <Ionicons name="add" size={23} color="white" />
          </Pressable>
        </View>
        <Text style={styles.subtitle}>Find a place for your pet's favorite things.</Text>
        <View style={styles.searchRow}>
          <TextInput style={styles.search} value={search} onChangeText={setSearch}
            placeholder="Search communities" returnKeyType="search" onSubmitEditing={() => setQuery(search.trim())} />
          <Pressable style={styles.searchButton} onPress={() => setQuery(search.trim())} accessibilityLabel="Search">
            <Ionicons name="search" size={20} color="white" />
          </Pressable>
        </View>
        {error && <View style={styles.errorBox}>
          <Text style={styles.error}>{error}</Text>
          <Pressable onPress={() => void loadFirst()}><Text style={styles.retry}>Try again</Text></Pressable>
        </View>}
      </>}
      renderItem={({ item }) => <Pressable style={styles.card}
        onPress={() => router.push({ pathname: '/community/[id]', params: { id: String(item.id) } })}>
        <Text style={styles.name}>{item.name}</Text>
        <Text style={styles.description} numberOfLines={2}>{item.description || 'A space for pets to connect.'}</Text>
        <Text style={styles.meta}>{item.memberCount} members{item.joinedByMe ? '  /  Joined' : ''}</Text>
      </Pressable>}
      ListEmptyComponent={loading ? <ActivityIndicator style={styles.empty} color={colors.accent} />
        : !error ? <Text style={styles.empty}>No communities found. Create one to get started.</Text> : null}
      ListFooterComponent={items.length > 0 ? <View style={styles.footer}>
        {loadingMore ? <ActivityIndicator color={colors.accent} /> : loadMoreError ? <Pressable onPress={() => void loadMore(true)}>
          <Text style={styles.error}>{loadMoreError}  Try again</Text>
        </Pressable> : nextPage === null ? <Text style={styles.meta}>That's everyone.</Text> : null}
      </View> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 42 },
  header: { height: 74, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  back: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  create: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center' },
  subtitle: { color: colors.muted, fontSize: 14, marginBottom: 18 },
  searchRow: { flexDirection: 'row', gap: 8, marginBottom: 20 },
  search: { flex: 1, backgroundColor: colors.card, borderRadius: 13, paddingHorizontal: 14, minHeight: 46, color: colors.ink },
  searchButton: { width: 46, borderRadius: 13, backgroundColor: colors.green, alignItems: 'center', justifyContent: 'center' },
  card: { backgroundColor: colors.card, borderRadius: 18, padding: 18, marginBottom: 12, borderWidth: 1, borderColor: colors.line },
  name: { color: colors.ink, fontSize: 18, fontWeight: '800' },
  description: { color: colors.muted, marginTop: 6, lineHeight: 19 },
  meta: { color: colors.green, fontSize: 12, fontWeight: '700', marginTop: 10 },
  empty: { minHeight: 200, textAlign: 'center', textAlignVertical: 'center', color: colors.muted },
  footer: { alignItems: 'center', minHeight: 48 },
  errorBox: { backgroundColor: colors.accentPale, borderRadius: 13, padding: 14, marginBottom: 16 },
  error: { color: '#B23725' },
  retry: { color: colors.accent, fontWeight: '800', marginTop: 8 },
});
