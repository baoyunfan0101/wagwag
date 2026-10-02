import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  DEV_PET_ID, followPet, getFollowStatus, getPet, unfollowPet,
  type FollowStatus, type Pet,
} from '@/lib/api';
import { colors } from '@/lib/theme';

export default function PetDetailScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const petId = Number(id);
  const [pet, setPet] = useState<Pet | null>(null);
  const [social, setSocial] = useState<FollowStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!Number.isInteger(petId) || petId < 1) {
      setError('This pet link is invalid.');
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [profile, status] = await Promise.all([getPet(petId), getFollowStatus(petId)]);
      setPet(profile);
      setSocial(status);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load this pet.');
    } finally {
      setLoading(false);
    }
  }, [petId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  async function toggleFollow() {
    if (!social || busy) return;
    setBusy(true);
    try {
      setSocial(await (social.followedByMe ? unfollowPet(petId) : followPet(petId)));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update follow.');
    } finally {
      setBusy(false);
    }
  }

  function showList(tab: 'followers' | 'following') {
    router.push({ pathname: '/social', params: { petId: String(petId), tab } });
  }

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={22} color={colors.ink} />
        </Pressable>
        <Text style={styles.headerTitle}>Pet profile</Text>
        <View style={styles.back} />
      </View>
      {loading && !pet ? <View style={styles.center}><ActivityIndicator color={colors.accent} /></View>
        : pet && social ? <>
          <View style={styles.card}>
            {pet.avatarUrl ? <Image source={{ uri: pet.avatarUrl }} style={styles.avatar} /> :
              <View style={styles.avatarFallback}><Ionicons name="paw" size={56} color={colors.accent} /></View>}
            <Text style={styles.name}>{pet.name}</Text>
            <Text style={styles.species}>{[pet.breed, pet.species].filter(Boolean).join(' / ')}</Text>
            <Text style={styles.bio}>{pet.bio || 'Every pet has a story to share.'}</Text>
          </View>
          <View style={styles.counts}>
            <Pressable style={styles.count} onPress={() => showList('followers')}>
              <Text style={styles.countNumber}>{social.followerCount}</Text>
              <Text style={styles.countLabel}>Followers</Text>
            </Pressable>
            <Pressable style={styles.count} onPress={() => showList('following')}>
              <Text style={styles.countNumber}>{social.followingCount}</Text>
              <Text style={styles.countLabel}>Following</Text>
            </Pressable>
          </View>
          {petId !== DEV_PET_ID && <Pressable style={[styles.button, social.followedByMe && styles.secondary]}
            onPress={() => void toggleFollow()} disabled={busy}>
            {busy ? <ActivityIndicator color={social.followedByMe ? colors.green : 'white'} />
              : <Text style={[styles.buttonText, social.followedByMe && styles.secondaryText]}>
                {social.followedByMe ? 'Following' : 'Follow pet'}
              </Text>}
          </Pressable>}
          {error && <Text style={styles.error}>{error}</Text>}
        </> : <View style={styles.center}>
          <Text style={styles.error}>{error || 'Pet not found.'}</Text>
          <Pressable onPress={() => void load()}><Text style={styles.retry}>Try again</Text></Pressable>
        </View>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 42 },
  header: { height: 74, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  back: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' },
  headerTitle: { color: colors.ink, fontSize: 17, fontWeight: '800' },
  center: { minHeight: 300, alignItems: 'center', justifyContent: 'center', gap: 13 },
  card: { backgroundColor: colors.card, borderRadius: 24, padding: 24, alignItems: 'center' },
  avatar: { width: 136, height: 136, borderRadius: 68 },
  avatarFallback: { width: 136, height: 136, borderRadius: 68, backgroundColor: colors.accentPale, alignItems: 'center', justifyContent: 'center' },
  name: { color: colors.ink, fontSize: 30, fontWeight: '900', marginTop: 16 },
  species: { color: colors.muted, fontSize: 14, marginTop: 4 },
  bio: { color: colors.ink, fontSize: 15, lineHeight: 23, textAlign: 'center', marginTop: 18 },
  counts: { flexDirection: 'row', gap: 12, marginTop: 16 },
  count: { flex: 1, backgroundColor: colors.card, borderRadius: 18, padding: 18, alignItems: 'center' },
  countNumber: { color: colors.ink, fontSize: 24, fontWeight: '900' },
  countLabel: { color: colors.muted, fontSize: 13, marginTop: 4 },
  button: { minHeight: 54, backgroundColor: colors.accent, borderRadius: 15, marginTop: 20, alignItems: 'center', justifyContent: 'center' },
  secondary: { backgroundColor: colors.greenPale },
  buttonText: { color: 'white', fontSize: 15, fontWeight: '800' },
  secondaryText: { color: colors.green },
  error: { color: '#B23725', textAlign: 'center', marginTop: 16 },
  retry: { color: colors.accent, fontWeight: '800' },
});
