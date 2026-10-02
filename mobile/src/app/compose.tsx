import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Image, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { createPost } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function ComposeScreen() {
  const router = useRouter();
  const [body, setBody] = useState('');
  const [imageUrl, setImageUrl] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function publish() {
    if (saving) return;
    const text = body.trim();
    const image = imageUrl.trim();
    if (!text && !image) {
      setError('Add a story or an image link.');
      return;
    }
    if (image && !/^https?:\/\/[^\s]+$/i.test(image)) {
      setError('Use a valid HTTP or HTTPS image link.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const post = await createPost({ body: text || null, imageUrl: image || null });
      router.replace({ pathname: '/post/[id]', params: { id: String(post.id) } });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not publish the post.');
    } finally {
      setSaving(false);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <KeyboardAvoidingView style={styles.fill} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Back to feed">
            <Ionicons name="arrow-back" size={22} color={colors.ink} />
          </Pressable>
          <Text style={styles.headerTitle}>New post</Text>
          <View style={styles.back} />
        </View>
        <Text style={styles.title}>Share a moment</Text>
        <Text style={styles.subtitle}>What has your pet been up to?</Text>
        <TextInput style={styles.story} value={body} onChangeText={setBody}
          placeholder="Tell their story..." placeholderTextColor="#9BA59D"
          multiline textAlignVertical="top" maxLength={2000} />
        <Text style={styles.counter}>{body.length}/2000</Text>
        <Text style={styles.label}>Image link (optional)</Text>
        <TextInput style={styles.link} value={imageUrl} onChangeText={setImageUrl}
          placeholder="https://example.com/photo.jpg" placeholderTextColor="#9BA59D"
          autoCapitalize="none" autoCorrect={false} keyboardType="url" maxLength={2048} />
        <Text style={styles.hint}>Paste a public image link to add a photo.</Text>
        {/^https?:\/\/[^\s]+$/i.test(imageUrl.trim()) &&
          <Image source={{ uri: imageUrl.trim() }} style={styles.preview} resizeMode="cover" />}
        {error && <Text style={styles.error}>{error}</Text>}
        <Pressable style={[styles.publish, saving && styles.disabled]} onPress={() => void publish()} disabled={saving}>
          {saving ? <ActivityIndicator color="white" /> : <>
            <Text style={styles.publishText}>Publish post</Text>
            <Ionicons name="arrow-forward" size={19} color="white" />
          </>}
        </Pressable>
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
  title: { color: colors.ink, fontSize: 32, fontWeight: '900', marginTop: 18 },
  subtitle: { color: colors.muted, fontSize: 15, marginTop: 6, marginBottom: 24 },
  story: { minHeight: 200, backgroundColor: colors.card, borderRadius: 20, borderWidth: 1, borderColor: colors.line, padding: 18, color: colors.ink, fontSize: 16, lineHeight: 24 },
  counter: { color: colors.muted, fontSize: 12, alignSelf: 'flex-end', marginTop: 8, marginBottom: 24 },
  label: { color: colors.ink, fontSize: 14, fontWeight: '800', marginBottom: 9 },
  link: { minHeight: 54, backgroundColor: colors.card, borderRadius: 14, borderWidth: 1, borderColor: colors.line, paddingHorizontal: 16, color: colors.ink, fontSize: 14 },
  hint: { color: colors.muted, fontSize: 12, marginTop: 8 },
  preview: { width: '100%', height: 230, borderRadius: 18, marginTop: 18, backgroundColor: colors.greenPale },
  error: { color: '#B23725', lineHeight: 20, marginTop: 18 },
  publish: { minHeight: 56, backgroundColor: colors.accent, borderRadius: 16, flexDirection: 'row', gap: 9, alignItems: 'center', justifyContent: 'center', marginTop: 27 },
  disabled: { opacity: 0.65 },
  publishText: { color: 'white', fontSize: 16, fontWeight: '800' },
});
