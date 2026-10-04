import { Ionicons } from '@expo/vector-icons';
import * as ImageManipulator from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Image, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { createPost, getCommunity, uploadPostImage, uploadPostVideo } from '@/lib/api';
import { PostVideo } from '@/components/PostVideo';
import { validateVideoSize, videoContentType } from '@/lib/postVideo';
import { colors } from '@/lib/theme';

export default function ComposeScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ communityId?: string }>();
  const communityId = params.communityId ? Number(params.communityId) : undefined;
  const [communityName, setCommunityName] = useState<string | null>(null);
  const [body, setBody] = useState('');
  const [selectedUris, setSelectedUris] = useState<string[]>([]);
  const uploadedKeys = useRef<Record<string, string>>({});
  const [video, setVideo] = useState<ImagePicker.ImagePickerAsset | null>(null);
  const videoKey = useRef<string | undefined>(undefined);
  const choosingRef = useRef(false);
  const savingRef = useRef(false);
  const [choosing, setChoosing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!communityId) return;
    getCommunity(communityId).then((community) => setCommunityName(community.name))
      .catch(() => setError('Could not load this community.'));
  }, [communityId]);

  async function chooseImages() {
    if (choosingRef.current || savingRef.current || video || selectedUris.length >= 4) return;
    choosingRef.current = true;
    setChoosing(true);
    try {
      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['images'], allowsMultipleSelection: true,
        selectionLimit: 4 - selectedUris.length, orderedSelection: true,
      });
      if (result.canceled) return;
      const converted = await Promise.all(result.assets.map(async (asset) => {
        const image = await ImageManipulator.manipulateAsync(
          asset.uri, [{ resize: { width: Math.min(asset.width, 1600) } }],
          { compress: 0.8, format: ImageManipulator.SaveFormat.JPEG },
        );
        return image.uri;
      }));
      setSelectedUris((current) => [...current, ...converted].slice(0, 4));
      setError(null);
    } catch {
      setError('Could not prepare the selected photos.');
    } finally { choosingRef.current = false; setChoosing(false); }
  }

  async function chooseVideo() {
    if (choosingRef.current || savingRef.current || selectedUris.length > 0) return;
    choosingRef.current = true;
    setChoosing(true);
    try {
      const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['videos'], allowsMultipleSelection: false });
      if (result.canceled) return;
      const asset = result.assets[0];
      videoContentType(asset);
      if (asset.fileSize !== undefined) validateVideoSize(asset.fileSize);
      videoKey.current = undefined;
      setVideo(asset);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not prepare the selected video.');
    } finally { choosingRef.current = false; setChoosing(false); }
  }

  async function publish() {
    if (savingRef.current || choosingRef.current) return;
    const text = body.trim();
    if (!text && selectedUris.length === 0 && !video) {
      setError('Add a story, photos, or a video.');
      return;
    }
    setSaving(true);
    savingRef.current = true;
    setError(null);
    try {
      const imageKeys: string[] = [];
      for (const uri of selectedUris) {
        const key = uploadedKeys.current[uri] || await uploadPostImage(uri);
        uploadedKeys.current[uri] = key;
        imageKeys.push(key);
      }
      if (video && !videoKey.current) videoKey.current = await uploadPostVideo(video);
      const post = await createPost({ body: text || null, imageKeys, videoKey: videoKey.current, communityId });
      router.replace({ pathname: '/post/[id]', params: { id: String(post.id) } });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not publish the post.');
    } finally {
      setSaving(false);
      savingRef.current = false;
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
        <Text style={styles.subtitle}>{communityName ? `Posting in ${communityName}` : 'What has your pet been up to?'}</Text>
        <TextInput style={styles.story} value={body} onChangeText={setBody}
          placeholder="Tell their story..." placeholderTextColor="#9BA59D"
          multiline textAlignVertical="top" maxLength={2000} editable={!saving} />
        <Text style={styles.counter}>{body.length}/2000</Text>
        <Text style={styles.label}>Photos ({selectedUris.length}/4)</Text>
        <Pressable style={styles.photoButton} onPress={() => void chooseImages()}
          disabled={saving || choosing || !!video || selectedUris.length >= 4} accessibilityLabel="Choose post photos">
          <Ionicons name="images-outline" size={20} color={colors.accent} />
          <Text style={styles.photoButtonText}>Choose photos</Text>
        </Pressable>
        {selectedUris.length > 0 && <View style={styles.photoGrid}>{selectedUris.map((uri) =>
          <View key={uri} style={styles.photoWrap}>
            <Image source={{ uri }} style={styles.photo} />
            <Pressable style={styles.removePhoto} onPress={() => setSelectedUris((current) => current.filter((item) => item !== uri))}
              disabled={saving} accessibilityLabel="Remove photo">
              <Ionicons name="close" size={17} color="white" />
            </Pressable>
          </View>)}</View>}
        <Text style={styles.label}>Or one video</Text>
        <Text style={styles.videoNote}>MP4, MOV, or WebM / up to 50 MB. MP4 works best across devices.</Text>
        <Pressable style={styles.photoButton} onPress={() => void chooseVideo()}
          disabled={saving || choosing || selectedUris.length > 0} accessibilityLabel="Choose post video">
          <Ionicons name="videocam-outline" size={20} color={colors.accent} />
          <Text style={styles.photoButtonText}>{video ? 'Choose another video' : 'Choose video'}</Text>
        </Pressable>
        {choosing && <ActivityIndicator color={colors.accent} />}
        {video && <View>
          <PostVideo key={video.uri} uri={video.uri} />
          <Pressable disabled={saving || choosing} onPress={() => { setVideo(null); videoKey.current = undefined; }} accessibilityLabel="Remove video">
            <Text style={styles.removeVideo}>Remove video</Text>
          </Pressable>
        </View>}
        {error && <Text style={styles.error}>{error}</Text>}
        <Pressable style={[styles.publish, (saving || choosing) && styles.disabled]} onPress={() => void publish()} disabled={saving || choosing}>
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
  photoButton: { minHeight: 54, backgroundColor: colors.accentPale, borderRadius: 14, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, marginBottom: 15 },
  photoButtonText: { color: colors.accent, fontWeight: '800' },
  photoGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 22 },
  photoWrap: { width: '48%', height: 150 },
  photo: { width: '100%', height: '100%', borderRadius: 14 },
  removePhoto: { position: 'absolute', top: 7, right: 7, width: 28, height: 28, borderRadius: 14, backgroundColor: '#0009', alignItems: 'center', justifyContent: 'center' },
  videoNote: { color: colors.muted, lineHeight: 20, marginBottom: 12 },
  removeVideo: { color: colors.accent, fontWeight: '800', textAlign: 'center', padding: 14 },
  error: { color: '#B23725', lineHeight: 20, marginTop: 18 },
  publish: { minHeight: 56, backgroundColor: colors.accent, borderRadius: 16, flexDirection: 'row', gap: 9, alignItems: 'center', justifyContent: 'center', marginTop: 27 },
  disabled: { opacity: 0.65 },
  publishText: { color: 'white', fontSize: 16, fontWeight: '800' },
});
