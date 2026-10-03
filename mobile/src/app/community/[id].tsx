import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { PostCard } from '@/components/PostCard';
import {
  getCommunity, getCommunityFeed, joinCommunity, leaveCommunity, likePost, unlikePost,
  type Community, type Post,
} from '@/lib/api';
import { colors } from '@/lib/theme';

export default function CommunityScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const communityId = Number(id);
  const [community, setCommunity] = useState<Community | null>(null);
  const [posts, setPosts] = useState<Post[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [busy, setBusy] = useState(false);
  const [busyLikeId, setBusyLikeId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const nextInFlight = useRef(false);
  const lastLoadedCursor = useRef<string | null>(null);

  const loadFirst = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    lastLoadedCursor.current = null;
    setNextCursor(null);
    setLoadMoreError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setPosts([]); }
    try {
      const [details, page] = await Promise.all([getCommunity(communityId), getCommunityFeed(communityId)]);
      if (current !== generation.current) return;
      setCommunity(details);
      setPosts(page.items);
      setNextCursor(page.nextCursor);
      setError(null);
    } catch (cause) {
      if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load this community.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, [communityId]);

  useFocusEffect(useCallback(() => { void loadFirst(); }, [loadFirst]));

  async function loadMore(retry = false) {
    if (!nextCursor || nextCursor === lastLoadedCursor.current || loading || refreshing
        || nextInFlight.current || (loadMoreError && !retry)) return;
    nextInFlight.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const page = await getCommunityFeed(communityId, 20, nextCursor);
      if (current !== generation.current) return;
      lastLoadedCursor.current = nextCursor;
      setPosts((existing) => {
        const ids = new Set(existing.map((post) => post.id));
        return [...existing, ...page.items.filter((post) => !ids.has(post.id))];
      });
      setNextCursor(page.nextCursor);
    } catch (cause) {
      if (current === generation.current) setLoadMoreError(cause instanceof Error ? cause.message : 'Could not load more posts.');
    } finally {
      nextInFlight.current = false;
      setLoadingMore(false);
    }
  }

  async function toggleJoin() {
    if (!community || busy) return;
    setBusy(true);
    try {
      setCommunity(await (community.joinedByMe ? leaveCommunity(communityId) : joinCommunity(communityId)));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update membership.');
    } finally { setBusy(false); }
  }

  async function toggleLike(post: Post) {
    if (busyLikeId !== null) return;
    setBusyLikeId(post.id);
    try {
      const updated = await (post.likedByMe ? unlikePost(post.id) : likePost(post.id));
      setPosts((existing) => existing.map((item) => item.id === updated.id ? updated : item));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update the like.');
    } finally { setBusyLikeId(null); }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={posts} keyExtractor={(post) => String(post.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void loadFirst(true)}
      onEndReached={() => void loadMore()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={22} color={colors.ink} />
          </Pressable>
          <Text style={styles.headerTitle}>Community</Text><View style={styles.back} />
        </View>
        {community && <View style={styles.hero}>
          <Text style={styles.name}>{community.name}</Text>
          <Text style={styles.description}>{community.description || 'A place for pets to connect.'}</Text>
          <Pressable onPress={() => router.push({ pathname: '/community-members', params: { id: String(communityId) } })}>
            <Text style={styles.members}>{community.memberCount} members  /  View members</Text>
          </Pressable>
          <Pressable style={[styles.join, community.joinedByMe && styles.joined]} onPress={() => void toggleJoin()} disabled={busy}>
            {busy ? <ActivityIndicator color="white" /> : <Text style={styles.joinText}>
              {community.joinedByMe ? 'Leave community' : 'Join community'}
            </Text>}
          </Pressable>
        </View>}
        {community?.joinedByMe && <Pressable style={styles.compose}
          onPress={() => router.push({ pathname: '/compose', params: { communityId: String(communityId) } })}>
          <Ionicons name="add" size={20} color="white" /><Text style={styles.composeText}>Post to community</Text>
        </Pressable>}
        <Text style={styles.feedTitle}>Community posts</Text>
        {error && <View style={styles.errorBox}>
          <Text style={styles.error}>{error}</Text>
          <Pressable onPress={() => void loadFirst()}><Text style={styles.retry}>Try again</Text></Pressable>
        </View>}
      </>}
      renderItem={({ item }) => <PostCard post={item}
        onOpen={() => router.push({ pathname: '/post/[id]', params: { id: String(item.id) } })}
        onPet={() => router.push({ pathname: '/pet/[id]', params: { id: String(item.petId) } })}
        onLike={() => void toggleLike(item)} likeBusy={busyLikeId === item.id} />}
      ListEmptyComponent={loading ? <ActivityIndicator style={styles.empty} color={colors.accent} />
        : !error ? <Text style={styles.empty}>No posts yet. Join and share the first story.</Text> : null}
      ListFooterComponent={posts.length > 0 ? <View style={styles.footer}>
        {loadingMore ? <ActivityIndicator color={colors.accent} /> : loadMoreError ?
          <Pressable onPress={() => void loadMore(true)}><Text style={styles.error}>{loadMoreError}  Try again</Text></Pressable>
          : nextCursor === null ? <Text style={styles.description}>You're all caught up.</Text> : null}
      </View> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 42 },
  header: { height: 74, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  back: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' },
  headerTitle: { color: colors.ink, fontSize: 20, fontWeight: '900' },
  hero: { backgroundColor: colors.card, borderRadius: 22, padding: 22, borderWidth: 1, borderColor: colors.line },
  name: { color: colors.ink, fontSize: 28, fontWeight: '900' },
  description: { color: colors.muted, marginTop: 8, lineHeight: 20 },
  members: { color: colors.green, marginTop: 16, fontWeight: '800' },
  join: { backgroundColor: colors.accent, borderRadius: 14, minHeight: 48, alignItems: 'center', justifyContent: 'center', marginTop: 18 },
  joined: { backgroundColor: colors.green },
  joinText: { color: 'white', fontWeight: '800' },
  compose: { backgroundColor: colors.accent, borderRadius: 14, minHeight: 50, marginTop: 16,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8 },
  composeText: { color: 'white', fontWeight: '800' },
  feedTitle: { color: colors.ink, fontSize: 20, fontWeight: '800', marginTop: 26, marginBottom: 14 },
  empty: { minHeight: 180, textAlign: 'center', textAlignVertical: 'center', color: colors.muted },
  footer: { minHeight: 50, alignItems: 'center' },
  errorBox: { backgroundColor: colors.accentPale, borderRadius: 13, padding: 14, marginBottom: 16 },
  error: { color: '#B23725' },
  retry: { color: colors.accent, fontWeight: '800', marginTop: 8 },
});
