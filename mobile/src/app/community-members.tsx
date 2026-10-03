import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getCommunityMembers, type CommunityMember } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function CommunityMembersScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const communityId = Number(id);
  const [items, setItems] = useState<CommunityMember[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const nextInFlight = useRef(false);
  const lastLoadedPage = useRef<number | null>(null);

  const loadFirst = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    lastLoadedPage.current = null;
    setNextPage(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setItems([]); }
    try {
      const page = await getCommunityMembers(communityId);
      if (current !== generation.current) return;
      setItems(page.items);
      setNextPage(page.nextPage);
      setError(null);
    } catch (cause) {
      if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load members.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, [communityId]);

  useFocusEffect(useCallback(() => { void loadFirst(); }, [loadFirst]));

  async function loadMore() {
    if (nextPage === null || nextPage === lastLoadedPage.current || loading || refreshing
        || nextInFlight.current || error) return;
    nextInFlight.current = true;
    const current = generation.current;
    setLoadingMore(true);
    try {
      const page = await getCommunityMembers(communityId, 20, nextPage);
      if (current !== generation.current) return;
      lastLoadedPage.current = nextPage;
      setItems((existing) => {
        const ids = new Set(existing.map((item) => item.id));
        return [...existing, ...page.items.filter((item) => !ids.has(item.id))];
      });
      setNextPage(page.nextPage);
    } catch (cause) {
      if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load more members.');
    } finally {
      nextInFlight.current = false;
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
          <Text style={styles.title}>Members</Text><View style={styles.back} />
        </View>
        {error && <Pressable style={styles.errorBox} onPress={() => void loadFirst()}>
          <Text style={styles.error}>{error}  Tap to retry.</Text>
        </Pressable>}
      </>}
      renderItem={({ item }) => <Pressable style={styles.card}
        onPress={() => router.push({ pathname: '/pet/[id]', params: { id: String(item.id) } })}>
        {item.avatarUrl ? <Image source={{ uri: item.avatarUrl }} style={styles.avatar} /> :
          <View style={styles.avatarFallback}><Ionicons name="paw" size={20} color={colors.accent} /></View>}
        <View><Text style={styles.name}>{item.name}</Text><Text style={styles.species}>{item.species}</Text></View>
      </Pressable>}
      ListEmptyComponent={loading ? <ActivityIndicator style={styles.empty} color={colors.accent} />
        : !error ? <Text style={styles.empty}>No members yet.</Text> : null}
      ListFooterComponent={loadingMore ? <ActivityIndicator color={colors.accent} /> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 42 },
  header: { height: 74, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 },
  back: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  card: { backgroundColor: colors.card, borderRadius: 16, padding: 14, flexDirection: 'row',
    alignItems: 'center', gap: 12, marginBottom: 10 },
  avatar: { width: 48, height: 48, borderRadius: 24 },
  avatarFallback: { width: 48, height: 48, borderRadius: 24, backgroundColor: colors.accentPale,
    alignItems: 'center', justifyContent: 'center' },
  name: { color: colors.ink, fontWeight: '800', fontSize: 16 },
  species: { color: colors.muted, fontSize: 12, marginTop: 3 },
  empty: { minHeight: 180, textAlign: 'center', textAlignVertical: 'center', color: colors.muted },
  errorBox: { backgroundColor: colors.accentPale, borderRadius: 12, padding: 14, marginBottom: 16 },
  error: { color: '#B23725' },
});
