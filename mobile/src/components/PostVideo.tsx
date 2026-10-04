import { Ionicons } from '@expo/vector-icons';
import { useEvent } from 'expo';
import { useFocusEffect } from 'expo-router';
import { useVideoPlayer, VideoView } from 'expo-video';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, AppState, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from '@/lib/theme';

function Player({ uri }: { uri: string }) {
  const player = useVideoPlayer(uri, value => { value.staysActiveInBackground = false; });
  const { status } = useEvent(player, 'statusChange', { status: player.status });
  useEffect(() => {
    const listener = AppState.addEventListener('change', state => { if (state !== 'active') player.pause(); });
    return () => listener.remove();
  }, [player]);
  return <View style={styles.frame}>
    <VideoView style={styles.video} player={player} nativeControls contentFit="contain"
      fullscreenOptions={{ enable: true }} allowsPictureInPicture={false} />
    {status === 'loading' && <ActivityIndicator style={styles.loading} color={colors.accent} />}
    {status === 'error' && <Text style={styles.error}>Could not play this video. Its format may not be supported on this device.</Text>}
  </View>;
}

export function PostVideo({ uri, thumbnailUrl, visible = true }: { uri: string; thumbnailUrl?: string | null; visible?: boolean }) {
  const [opened, setOpened] = useState(false);
  const [focused, setFocused] = useState(false);
  useFocusEffect(useCallback(() => {
    setFocused(true);
    return () => setFocused(false);
  }, []));
  useEffect(() => { if (!visible || !focused) setOpened(false); }, [visible, focused]);
  // Unmounting releases the player; avoid calling it after the native object is released.
  return opened && visible && focused ? <Player key={uri} uri={uri} /> :
    <Pressable style={styles.open} onPress={() => setOpened(true)} disabled={!visible || !focused} accessibilityLabel="Watch video">
      {thumbnailUrl && <Image source={{ uri: thumbnailUrl }} style={styles.thumbnail} />}
      <View style={styles.play}>
        <Ionicons name="play-circle-outline" size={42} color={colors.green} />
        <Text style={styles.label}>Watch video</Text>
      </View>
    </Pressable>;
}

const styles = StyleSheet.create({
  frame: { marginTop: 12 },
  video: { width: '100%', height: 280, borderRadius: 15, backgroundColor: '#172820' },
  open: { minHeight: 150, borderRadius: 15, backgroundColor: colors.greenPale,
    alignItems: 'center', justifyContent: 'center', gap: 10, marginTop: 12 },
  thumbnail: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, borderRadius: 15 },
  play: { alignItems: 'center', gap: 6, padding: 12, borderRadius: 14, backgroundColor: '#F2F8F0E6' },
  label: { color: colors.green, fontWeight: '800' },
  loading: { position: 'absolute', top: 125, alignSelf: 'center' },
  error: { color: '#B23725', lineHeight: 20, marginTop: 8 },
});
