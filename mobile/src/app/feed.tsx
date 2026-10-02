import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { PostCard } from '@/components/PostCard';
import { getFeed, likePost, unlikePost, type Post } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function FeedScreen() {
  const router = useRouter();
  const [posts, setPosts] = useState<Post[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [busyLikeId, setBusyLikeId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (refresh = false) => {
    if (refresh) setRefreshing(true);
    else setLoading(true);
    try {
      setPosts(await getFeed());
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load the feed.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  async function toggleLike(post: Post) {
    if (busyLikeId !== null) return;
    setBusyLikeId(post.id);
    try {
      const updated = await (post.likedByMe ? unlikePost(post.id) : likePost(post.id));
      setPosts((current) => current.map((item) => item.id === updated.id ? updated : item));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update the like.');
    } finally {
      setBusyLikeId(null);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} refreshControl={
      <RefreshControl refreshing={refreshing} onRefresh={() => void load(true)} tintColor={colors.accent} />
    }>
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
        <Pressable onPress={() => void load()}><Text style={styles.retry}>Try again</Text></Pressable>
      </View>}
      {loading && posts.length === 0 ? <View style={styles.center}>
        <ActivityIndicator size="large" color={colors.accent} />
      </View> : posts.length === 0 ? <View style={styles.empty}>
        <Ionicons name="paw-outline" size={46} color={colors.accent} />
        <Text style={styles.emptyTitle}>No posts yet</Text>
        <Text style={styles.emptyText}>Share the first WagWag moment.</Text>
        <Pressable style={styles.button} onPress={() => router.push('/compose')}>
          <Text style={styles.buttonText}>Create a post</Text>
        </Pressable>
      </View> : posts.map((post) => <PostCard key={post.id} post={post}
        onOpen={() => router.push({ pathname: '/post/[id]', params: { id: String(post.id) } })}
        onLike={() => void toggleLike(post)} likeBusy={busyLikeId === post.id} />)}
    </ScrollView>
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
});
