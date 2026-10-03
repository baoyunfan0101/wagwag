import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Image, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { PostCard } from '@/components/PostCard';
import { createComment, getComments, getPost, likePost, unlikePost, type Comment, type Post } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function PostDetailScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const postId = Number(id);
  const [post, setPost] = useState<Post | null>(null);
  const [comments, setComments] = useState<Comment[]>([]);
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [likeBusy, setLikeBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!Number.isInteger(postId) || postId < 1) {
      setError('This post link is invalid.');
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [result, replies] = await Promise.all([getPost(postId), getComments(postId)]);
      setPost(result);
      setComments(replies);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load the post.');
    } finally {
      setLoading(false);
    }
  }, [postId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  async function toggleLike() {
    if (!post || likeBusy) return;
    setLikeBusy(true);
    setPost({ ...post, likedByMe: !post.likedByMe,
      likeCount: post.likeCount + (post.likedByMe ? -1 : 1) });
    try {
      const updated = await (post.likedByMe ? unlikePost(post.id) : likePost(post.id));
      setPost((current) => current && { ...current,
        likedByMe: updated.likedByMe, likeCount: updated.likeCount });
      setError(null);
    } catch (cause) {
      setPost((current) => current && { ...current, likedByMe: post.likedByMe, likeCount: post.likeCount });
      setError(cause instanceof Error ? cause.message : 'Could not update the like.');
    } finally {
      setLikeBusy(false);
    }
  }

  async function sendComment() {
    if (!post || sending) return;
    const body = draft.trim();
    if (!body) {
      setError('Write a comment before sending.');
      return;
    }
    setSending(true);
    const pending: Comment = {
      id: -Date.now(), postId: post.id, petId: post.petId,
      petName: post.petName, petAvatarUrl: post.petAvatarUrl,
      body, createdAt: new Date().toISOString(),
    };
    setComments((current) => [...current, pending]);
    setPost((current) => current && { ...current, commentCount: current.commentCount + 1 });
    setDraft('');
    try {
      const comment = await createComment(post.id, body);
      setComments((current) => current.map((item) => item.id === pending.id ? comment : item));
      setError(null);
    } catch (cause) {
      setComments((current) => current.filter((item) => item.id !== pending.id));
      setPost((current) => current && { ...current, commentCount: current.commentCount - 1 });
      setDraft(body);
      setError(cause instanceof Error ? cause.message : 'Could not send the comment.');
    } finally {
      setSending(false);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <KeyboardAvoidingView style={styles.fill} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Back to feed">
            <Ionicons name="arrow-back" size={22} color={colors.ink} />
          </Pressable>
          <Text style={styles.headerTitle}>Post</Text>
          <View style={styles.back} />
        </View>
        {loading && !post ? <View style={styles.center}><ActivityIndicator size="large" color={colors.accent} /></View> :
          post ? <>
            <PostCard post={post} onLike={() => void toggleLike()} likeBusy={likeBusy}
              onPet={() => router.push({ pathname: '/pet/[id]', params: { id: String(post.petId) } })}
              onCommunity={post.communityId ? () => router.push({ pathname: '/community/[id]',
                params: { id: String(post.communityId) } }) : undefined} />
            <Text style={styles.sectionTitle}>Comments</Text>
            {comments.length === 0 && <Text style={styles.empty}>Be the first to say hello.</Text>}
            {comments.map((comment) => <View key={comment.id} style={styles.comment}>
              {comment.petAvatarUrl ? <Image source={{ uri: comment.petAvatarUrl }} style={styles.avatar} /> :
                <View style={styles.avatarPlaceholder}><Ionicons name="paw" size={17} color={colors.accent} /></View>}
              <View style={styles.commentContent}>
                <Text style={styles.commentName}>{comment.petName}</Text>
                <Text style={styles.commentBody}>{comment.body}</Text>
                <Text style={styles.commentDate}>{new Date(comment.createdAt).toLocaleString()}</Text>
              </View>
            </View>)}
            <Text style={styles.label}>Add a comment</Text>
            <TextInput style={styles.input} value={draft} onChangeText={setDraft}
              placeholder="Say something kind..." placeholderTextColor="#9BA59D"
              multiline textAlignVertical="top" maxLength={500} editable={!sending} />
            {error && <Text style={styles.error}>{error}</Text>}
            <Pressable style={[styles.send, sending && styles.disabled]} onPress={() => void sendComment()} disabled={sending}>
              {sending ? <ActivityIndicator color="white" /> : <>
                <Text style={styles.sendText}>Post comment</Text>
                <Ionicons name="send" size={18} color="white" />
              </>}
            </Pressable>
          </> : <View style={styles.center}>
            <Text style={styles.error}>{error || 'Post not found.'}</Text>
            <Pressable onPress={() => void load()}><Text style={styles.retry}>Try again</Text></Pressable>
          </View>}
      </ScrollView>
    </KeyboardAvoidingView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  fill: { flex: 1 },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 42 },
  header: { height: 74, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  back: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' },
  headerTitle: { color: colors.ink, fontSize: 17, fontWeight: '800' },
  center: { minHeight: 300, justifyContent: 'center', alignItems: 'center', gap: 14 },
  sectionTitle: { color: colors.ink, fontSize: 21, fontWeight: '800', marginTop: 18, marginBottom: 14 },
  empty: { color: colors.muted, marginBottom: 18 },
  comment: { flexDirection: 'row', gap: 11, backgroundColor: colors.card, borderRadius: 16, padding: 15, marginBottom: 10 },
  avatar: { width: 36, height: 36, borderRadius: 18 },
  avatarPlaceholder: { width: 36, height: 36, borderRadius: 18, backgroundColor: colors.accentPale, alignItems: 'center', justifyContent: 'center' },
  commentContent: { flex: 1 },
  commentName: { color: colors.ink, fontSize: 14, fontWeight: '800' },
  commentBody: { color: colors.ink, fontSize: 14, lineHeight: 21, marginTop: 5 },
  commentDate: { color: colors.muted, fontSize: 11, marginTop: 7 },
  label: { color: colors.ink, fontSize: 14, fontWeight: '800', marginTop: 18, marginBottom: 9 },
  input: { minHeight: 95, backgroundColor: colors.card, borderRadius: 14, borderWidth: 1, borderColor: colors.line, padding: 15, color: colors.ink, fontSize: 15 },
  error: { color: '#B23725', lineHeight: 20, marginTop: 14, textAlign: 'center' },
  retry: { color: colors.accent, fontWeight: '800' },
  send: { minHeight: 54, backgroundColor: colors.accent, borderRadius: 15, flexDirection: 'row', gap: 8, alignItems: 'center', justifyContent: 'center', marginTop: 17 },
  disabled: { opacity: 0.65 },
  sendText: { color: 'white', fontSize: 15, fontWeight: '800' },
});
