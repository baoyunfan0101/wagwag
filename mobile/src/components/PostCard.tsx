import { Ionicons } from '@expo/vector-icons';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import type { Post } from '@/lib/api';
import { colors } from '@/lib/theme';

type Props = {
  post: Post;
  onOpen?: () => void;
  onPet?: () => void;
  onCommunity?: () => void;
  onLike: () => void;
  likeBusy?: boolean;
};

export function PostCard({ post, onOpen, onPet, onCommunity, onLike, likeBusy = false }: Props) {
  const images = post.imageUrls;
  const content = <>
    <View style={styles.header}>
      {post.petAvatarUrl ? <Image source={{ uri: post.petAvatarUrl }} style={styles.avatar} /> :
        <View style={styles.avatarPlaceholder}><Ionicons name="paw" size={20} color={colors.accent} /></View>}
      <View style={styles.author}>
        <Text style={styles.name}>{post.petName}</Text>
        <Text style={styles.date}>{new Date(post.createdAt).toLocaleString()}</Text>
      </View>
      {onOpen && <Ionicons name="chevron-forward" size={20} color={colors.muted} />}
    </View>
    {post.communityName && <Text style={styles.community}>In {post.communityName}</Text>}
    {post.body && <Text style={styles.body}>{post.body}</Text>}
    {images.length === 1 && <Image source={{ uri: images[0] }} style={styles.image} resizeMode="cover" />}
    {images.length > 1 && <View style={styles.imageGrid}>
      {images.map((url) => <Image key={url} source={{ uri: url }} style={styles.gridImage} resizeMode="cover" />)}
    </View>}
  </>;

  return <View style={styles.card}>
    {onOpen ? <Pressable onPress={onOpen} accessibilityLabel={`View post by ${post.petName}`}>
      {content}
    </Pressable> : <View>{content}</View>}
    <View style={styles.actions}>
      <Pressable style={styles.action} onPress={onLike} disabled={likeBusy}
        accessibilityLabel={post.likedByMe ? 'Unlike post' : 'Like post'}>
        <Ionicons name={post.likedByMe ? 'heart' : 'heart-outline'} size={21}
          color={post.likedByMe ? colors.accent : colors.muted} />
        <Text style={[styles.actionText, post.likedByMe && styles.liked]}>{post.likeCount}</Text>
      </Pressable>
      {onOpen ? <Pressable style={styles.action} onPress={onOpen} accessibilityLabel="View comments">
        <Ionicons name="chatbubble-outline" size={19} color={colors.muted} />
        <Text style={styles.actionText}>{post.commentCount}</Text>
      </Pressable> : <View style={styles.action}>
        <Ionicons name="chatbubble-outline" size={19} color={colors.muted} />
        <Text style={styles.actionText}>{post.commentCount}</Text>
      </View>}
      {onPet && <Pressable style={styles.action} onPress={onPet} accessibilityLabel={`View ${post.petName}'s profile`}>
        <Ionicons name="paw-outline" size={19} color={colors.muted} />
        <Text style={styles.actionText}>Pet</Text>
      </Pressable>}
      {onCommunity && <Pressable style={styles.action} onPress={onCommunity} accessibilityLabel="View community">
        <Ionicons name="people-outline" size={19} color={colors.muted} />
        <Text style={styles.actionText}>Community</Text>
      </Pressable>}
    </View>
  </View>;
}

const styles = StyleSheet.create({
  card: { backgroundColor: colors.card, borderRadius: 22, borderWidth: 1, borderColor: colors.line, padding: 18, marginBottom: 16 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 11 },
  avatar: { width: 42, height: 42, borderRadius: 21 },
  avatarPlaceholder: { width: 42, height: 42, borderRadius: 21, backgroundColor: colors.accentPale, alignItems: 'center', justifyContent: 'center' },
  author: { flex: 1 },
  name: { color: colors.ink, fontSize: 15, fontWeight: '800' },
  date: { color: colors.muted, fontSize: 11, marginTop: 3 },
  community: { color: colors.green, fontSize: 12, fontWeight: '800', marginTop: 13 },
  body: { color: colors.ink, fontSize: 15, lineHeight: 23, marginTop: 16, marginBottom: 12 },
  image: { width: '100%', height: 260, borderRadius: 15, marginTop: 12, backgroundColor: colors.greenPale },
  imageGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 },
  gridImage: { width: '48%', height: 155, borderRadius: 12, backgroundColor: colors.greenPale },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 18, borderTopColor: colors.line, borderTopWidth: 1, marginTop: 17, paddingTop: 14 },
  action: { flexDirection: 'row', alignItems: 'center', gap: 7, minWidth: 45 },
  actionText: { color: colors.muted, fontSize: 14, fontWeight: '700' },
  liked: { color: colors.accent },
});
