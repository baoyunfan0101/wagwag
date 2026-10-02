import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { PostCard } from '@/components/PostCard';
import { getFeed, likePost, unlikePost, type Post } from '@/lib/api';
import { colors } from '@/lib/theme';

const PAGE_SIZE = 20;

export default function FeedScreen() {
  const router = useRouter();
  const [posts, setPosts] = useState<Post[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [busyLikeId, setBusyLikeId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const firstPageInFlight = useRef(false);
  const nextPageInFlight = useRef(false);
  const lastLoadedCursor = useRef<string | null>(null);

  const loadFirstPage = useCallback(async (refresh = false) => {
    if (firstPageInFlight.current) return;
    firstPageInFlight.current = true;
    const requestGeneration = ++generation.current;
    lastLoadedCursor.current = null;
    setNextCursor(null);
    setLoadMoreError(null);
    if (refresh) setRefreshing(true);
    else setLoading(true);
    try {
      const page = await getFeed(PAGE_SIZE);
      if (requestGeneration !== generation.current) return;
      setPosts(page.items);
      setNextCursor(page.nextCursor);
      setError(null);
    } catch (cause) {
      if (requestGeneration === generation.current) {
        setError(cause instanceof Error ? cause.message : 'Could not load the feed.');
      }
    } finally {
      firstPageInFlight.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useFocusEffect(useCallback(() => { void loadFirstPage(); }, [loadFirstPage]));

  async function loadNextPage(retry = false) {
    if (!nextCursor || nextCursor === lastLoadedCursor.current || firstPageInFlight.current
        || nextPageInFlight.current || (loadMoreError && !retry)) return;
    nextPageInFlight.current = true;
    const requestGeneration = generation.current;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const page = await getFeed(PAGE_SIZE, nextCursor);
      if (requestGeneration !== generation.current) return;
      lastLoadedCursor.current = nextCursor;
      setPosts((current) => {
        const existing = new Set(current.map((post) => post.id));
        return [...current, ...page.items.filter((post) => {
          if (existing.has(post.id)) return false;
          existing.add(post.id);
          return true;
        })];
      });
      setNextCursor(page.nextCursor);
    } catch (cause) {
      if (requestGeneration === generation.current) {
        setLoadMoreError(cause instanceof Error ? cause.message : 'Could not load more posts.');
      }
    } finally {
      nextPageInFlight.current = false;
      setLoadingMore(false);
    }
  }

  async function toggleLike(post: Post) {
    if (busyLikeId !== null) return;
    setBusyLikeId(post.id);
    const likedByMe = !post.likedByMe;
    const likeCount = post.likeCount + (likedByMe ? 1 : -1);
    setPosts((current) => current.map((item) => item.id === post.id
      ? { ...item, likedByMe, likeCount } : item));
    try {
      const updated = await (post.likedByMe ? unlikePost(post.id) : likePost(post.id));
      setPosts((current) => current.map((item) => item.id === updated.id
        ? { ...item, likeCount: updated.likeCount, likedByMe: updated.likedByMe } : item));
      setError(null);
    } catch (cause) {
      setPosts((current) => current.map((item) => item.id === post.id
        ? { ...item, likedByMe: post.likedByMe, likeCount: post.likeCount } : item));
      setError(cause instanceof Error ? cause.message : 'Could not update the like.');
    } finally {
      setBusyLikeId(null);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList
      data={posts}
      keyExtractor={(post) => String(post.id)}
      contentContainerStyle={styles.content}
      refreshing={refreshing}
      onRefresh={() => void loadFirstPage(true)}
      onEndReached={() => void loadNextPage()}
      onEndReachedThreshold={0.5}
      renderItem={({ item }) => <PostCard post={item}
        onOpen={() => router.push({ pathname: '/post/[id]', params: { id: String(item.id) } })}
        onLike={() => void toggleLike(item)} likeBusy={busyLikeId === item.id} />}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Back to profile">
            <Ionicons name="arrow-back" size={22} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>The Feed</Text>
          <Pressable style={styles.compose} onPress={() => router.push('/compose')} accessibilityLabel="Create post">
            <Ionicons name="add" size={25} color="white" />
          </Pressable>
        </View>
        <Text style={styles.intro}>A little corner for every pet's story.</Text>
        {error && <View style={styles.errorBox}>
          <Text style={styles.error}>{error}</Text>
          <Pressable onPress={() => void loadFirstPage()}><Text style={styles.retry}>Try again</Text></Pressable>
        </View>}
      </>}
      ListEmptyComponent={loading ? <View style={styles.center}>
        <ActivityIndicator size="large" color={colors.accent} />
      </View> : !error ? <View style={styles.empty}>
        <Ionicons name="paw-outline" size={46} color={colors.accent} />
        <Text style={styles.emptyTitle}>No posts yet</Text>
        <Text style={styles.emptyText}>Share the first WagWag moment.</Text>
        <Pressable style={styles.button} onPress={() => router.push('/compose')}>
          <Text style={styles.buttonText}>Create a post</Text>
        </Pressable>
      </View> : null}
      ListFooterComponent={posts.length > 0 ? <View style={styles.footer}>
        {loadingMore ? <ActivityIndicator color={colors.accent} />
          : loadMoreError ? <View style={styles.errorBox}>
            <Text style={styles.error}>{loadMoreError}</Text>
            <Pressable onPress={() => void loadNextPage(true)}><Text style={styles.retry}>Try again</Text></Pressable>
          </View>
          : !nextCursor && !loading && !refreshing ? <Text style={styles.end}>You're all caught up.</Text> : null}
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
  compose: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center' },
  intro: { color: colors.muted, fontSize: 14, marginBottom: 22 },
  center: { minHeight: 240, justifyContent: 'center' },
  empty: { minHeight: 360, backgroundColor: colors.card, borderRadius: 24, alignItems: 'center', justifyContent: 'center', padding: 28 },
  emptyTitle: { color: colors.ink, fontSize: 22, fontWeight: '800', marginTop: 15 },
  emptyText: { color: colors.muted, textAlign: 'center', marginTop: 8 },
  button: { backgroundColor: colors.accent, borderRadius: 14, paddingHorizontal: 22, paddingVertical: 14, marginTop: 22 },
  buttonText: { color: 'white', fontWeight: '800' },
  errorBox: { backgroundColor: colors.accentPale, borderRadius: 13, padding: 14, marginBottom: 16 },
  error: { color: '#B23725', lineHeight: 20 },
  retry: { color: colors.accent, fontWeight: '800', marginTop: 8 },
  footer: { minHeight: 50, justifyContent: 'center' },
  end: { color: colors.muted, textAlign: 'center' },
});
