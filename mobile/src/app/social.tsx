import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  DEV_PET_ID, discoverPets, followPet, getFollowStatus, getFollowers, getFollowing, getPet, unfollowPet,
  type FollowStatus, type PetListPage, type PetSummary,
} from '@/lib/api';
import { colors } from '@/lib/theme';

type Mode = 'discover' | 'following' | 'followers';
const PAGE_SIZE = 20;

function listPage(mode: Mode, petId: number, page: number): Promise<PetListPage> {
  if (mode === 'following') return getFollowing(petId, PAGE_SIZE, page);
  if (mode === 'followers') return getFollowers(petId, PAGE_SIZE, page);
  return discoverPets(PAGE_SIZE, page);
}

export default function SocialScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ petId?: string; tab?: Mode }>();
  const petId = Number(params.petId || DEV_PET_ID);
  const [mode, setMode] = useState<Mode>(params.tab || 'discover');
  const [petName, setPetName] = useState('Pet');
  const [status, setStatus] = useState<FollowStatus | null>(null);
  const [items, setItems] = useState<PetSummary[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const nextInFlight = useRef(false);
  const lastLoadedPage = useRef<number | null>(null);

  useEffect(() => {
    if (params.tab) setMode(params.tab);
  }, [params.tab]);

  useEffect(() => {
    let active = true;
    Promise.all([getPet(petId), getFollowStatus(petId)])
      .then(([pet, result]) => {
        if (active) {
          setPetName(pet.name);
          setStatus(result);
        }
      }).catch(() => { if (active) setPetName('Pet'); });
    return () => { active = false; };
  }, [petId]);

  const loadFirst = useCallback(async (refresh = false) => {
    const requestGeneration = ++generation.current;
    lastLoadedPage.current = null;
    setNextPage(null);
    setLoadMoreError(null);
    if (refresh) setRefreshing(true);
    else {
      setLoading(true);
      setItems([]);
    }
    try {
      const result = await listPage(mode, petId, 0);
      if (requestGeneration !== generation.current) return;
      setItems(result.items);
      setNextPage(result.nextPage);
      setError(null);
    } catch (cause) {
      if (requestGeneration === generation.current) {
        setError(cause instanceof Error ? cause.message : 'Could not load pets.');
      }
    } finally {
      if (requestGeneration === generation.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [mode, petId]);

  useFocusEffect(useCallback(() => {
    void loadFirst();
    getFollowStatus(petId).then(setStatus).catch(() => {});
  }, [loadFirst, petId]));

  async function loadMore(retry = false) {
    if (nextPage === null || nextPage === lastLoadedPage.current || loading || refreshing
        || nextInFlight.current || (loadMoreError && !retry)) return;
    nextInFlight.current = true;
    const requestGeneration = generation.current;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const result = await listPage(mode, petId, nextPage);
      if (requestGeneration !== generation.current) return;
      lastLoadedPage.current = nextPage;
      setItems((current) => {
        const ids = new Set(current.map((pet) => pet.id));
        return [...current, ...result.items.filter((pet) => {
          if (ids.has(pet.id)) return false;
          ids.add(pet.id);
          return true;
        })];
      });
      setNextPage(result.nextPage);
    } catch (cause) {
      if (requestGeneration === generation.current) {
        setLoadMoreError(cause instanceof Error ? cause.message : 'Could not load more pets.');
      }
    } finally {
      nextInFlight.current = false;
      setLoadingMore(false);
    }
  }

  async function toggleFollow(pet: PetSummary) {
    if (busyId !== null) return;
    setBusyId(pet.id);
    try {
      const status = await (pet.followedByMe ? unfollowPet(pet.id) : followPet(pet.id));
      if (mode === 'following' && petId === DEV_PET_ID && !status.followedByMe) {
        await loadFirst();
      } else {
        setItems((current) => current.map((item) => item.id === pet.id
          ? { ...item, followedByMe: status.followedByMe } : item));
        setError(null);
      }
      getFollowStatus(petId).then(setStatus).catch(() => {});
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update follow.');
    } finally {
      setBusyId(null);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={items} keyExtractor={(pet) => String(pet.id)}
      contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void loadFirst(true)}
      onEndReached={() => void loadMore()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={22} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Friends</Text>
          <View style={styles.back} />
        </View>
        <Text style={styles.subtitle}>{petName}'s pet circle</Text>
        <View style={styles.tabs}>{(['discover', 'following', 'followers'] as const).map((tab) =>
          <Pressable key={tab} style={[styles.tab, mode === tab && styles.activeTab]} onPress={() => setMode(tab)}>
            <Text style={[styles.tabText, mode === tab && styles.activeTabText]}>
              {tab === 'discover' ? 'Discover' : tab === 'following'
                ? `Following ${status?.followingCount ?? ''}` : `Followers ${status?.followerCount ?? ''}`}
            </Text>
          </Pressable>)}</View>
        {error && <View style={styles.errorBox}>
          <Text style={styles.error}>{error}</Text>
          <Pressable onPress={() => void loadFirst()}><Text style={styles.retry}>Try again</Text></Pressable>
        </View>}
      </>}
      renderItem={({ item }) => <View style={styles.card}>
        <Pressable style={styles.petInfo} onPress={() => router.push({ pathname: '/pet/[id]', params: { id: String(item.id) } })}>
          {item.avatarUrl ? <Image source={{ uri: item.avatarUrl }} style={styles.avatar} /> :
            <View style={styles.avatarFallback}><Ionicons name="paw" size={23} color={colors.accent} /></View>}
          <View style={styles.petText}>
            <Text style={styles.name}>{item.name}</Text>
            <Text style={styles.species}>{item.species}</Text>
          </View>
        </Pressable>
        {item.id !== DEV_PET_ID && <Pressable style={[styles.follow, item.followedByMe && styles.following]}
          onPress={() => void toggleFollow(item)} disabled={busyId === item.id}>
          <Text style={[styles.followText, item.followedByMe && styles.followingText]}>
            {item.followedByMe ? 'Following' : 'Follow'}
          </Text>
        </Pressable>}
      </View>}
      ListEmptyComponent={loading ? <View style={styles.center}><ActivityIndicator color={colors.accent} /></View>
        : !error ? <View style={styles.center}>
          <Ionicons name="people-outline" size={44} color={colors.accent} />
          <Text style={styles.empty}>No pets here yet.</Text>
        </View> : null}
      ListFooterComponent={items.length > 0 ? <View style={styles.footer}>
        {loadingMore ? <ActivityIndicator color={colors.accent} />
          : loadMoreError ? <View style={styles.errorBox}>
            <Text style={styles.error}>{loadMoreError}</Text>
            <Pressable onPress={() => void loadMore(true)}><Text style={styles.retry}>Try again</Text></Pressable>
          </View>
          : nextPage === null && !loading ? <Text style={styles.end}>You're all caught up.</Text> : null}
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
  subtitle: { color: colors.muted, fontSize: 14, marginBottom: 18 },
  tabs: { flexDirection: 'row', gap: 7, marginBottom: 20 },
  tab: { flex: 1, alignItems: 'center', paddingVertical: 11, borderRadius: 12, backgroundColor: colors.card },
  activeTab: { backgroundColor: colors.green },
  tabText: { color: colors.muted, fontSize: 12, fontWeight: '800' },
  activeTabText: { color: 'white' },
  card: { backgroundColor: colors.card, borderRadius: 18, padding: 15, marginBottom: 11, flexDirection: 'row', alignItems: 'center', gap: 10 },
  petInfo: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 11 },
  avatar: { width: 48, height: 48, borderRadius: 24 },
  avatarFallback: { width: 48, height: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.accentPale },
  petText: { flex: 1 },
  name: { color: colors.ink, fontSize: 15, fontWeight: '800' },
  species: { color: colors.muted, fontSize: 12, marginTop: 3 },
  follow: { backgroundColor: colors.accent, paddingHorizontal: 13, paddingVertical: 10, borderRadius: 11 },
  following: { backgroundColor: colors.greenPale },
  followText: { color: 'white', fontSize: 12, fontWeight: '800' },
  followingText: { color: colors.green },
  center: { minHeight: 260, alignItems: 'center', justifyContent: 'center', gap: 12 },
  empty: { color: colors.muted, textAlign: 'center' },
  errorBox: { backgroundColor: colors.accentPale, borderRadius: 13, padding: 14, marginBottom: 16 },
  error: { color: '#B23725', lineHeight: 20 },
  retry: { color: colors.accent, fontWeight: '800', marginTop: 8 },
  footer: { minHeight: 50, justifyContent: 'center' },
  end: { color: colors.muted, textAlign: 'center' },
});
