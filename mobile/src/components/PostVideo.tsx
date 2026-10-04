import { Ionicons } from '@expo/vector-icons';
import { useEvent } from 'expo';
import { useFocusEffect } from 'expo-router';
import { useVideoPlayer, VideoView } from 'expo-video';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, AppState, Pressable, StyleSheet, Text, View } from 'react-native';
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

export function PostVideo({ uri, visible = true }: { uri: string; visible?: boolean }) {
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
      <Ionicons name="play-circle-outline" size={42} color={colors.green} />
      <Text style={styles.label}>Watch video</Text>
    </Pressable>;
}

const styles = StyleSheet.create({
  frame: { marginTop: 12 },
  video: { width: '100%', height: 280, borderRadius: 15, backgroundColor: '#172820' },
  open: { minHeight: 150, borderRadius: 15, backgroundColor: colors.greenPale,
    alignItems: 'center', justifyContent: 'center', gap: 10, marginTop: 12 },
  label: { color: colors.green, fontWeight: '800' },
  loading: { position: 'absolute', top: 125, alignSelf: 'center' },
  error: { color: '#B23725', lineHeight: 20, marginTop: 8 },
});
