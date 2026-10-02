import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  DEV_PET_ID, blockPet, followPet, getFollowStatus, getPet, mutePet, setPetPrivacy,
  unblockPet, unfollowPet, unmutePet,
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
    await updateSocial(() => social.followedByMe || social.requestedByMe
      ? unfollowPet(petId) : followPet(petId));
  }

  async function updateSocial(action: () => Promise<FollowStatus>) {
    if (busy) return;
    setBusy(true);
    try {
      setSocial(await action());
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update follow.');
    } finally {
      setBusy(false);
    }
  }

  async function togglePrivacy() {
    if (!pet || busy) return;
    setBusy(true);
    try {
      const updated = await setPetPrivacy(petId, !pet.privateProfile);
      setPet(updated);
      setSocial((current) => current && { ...current, privateProfile: updated.privateProfile });
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update privacy.');
    } finally {
      setBusy(false);
    }
  }

  function showList(tab: 'followers' | 'following' | 'requests') {
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
            {pet.privateProfile && <Text style={styles.privateBadge}>Private profile</Text>}
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
          {petId === DEV_PET_ID ? <>
            <Pressable style={[styles.button, styles.secondary]} onPress={() => void togglePrivacy()} disabled={busy}>
              <Text style={[styles.buttonText, styles.secondaryText]}>
                {pet.privateProfile ? 'Make profile public' : 'Make profile private'}
              </Text>
            </Pressable>
            <Text style={styles.note}>Private posts and follow lists are visible only to approved followers.</Text>
            <Pressable onPress={() => showList('requests')}><Text style={styles.requests}>Review follow requests</Text></Pressable>
          </> : <>
            {!social.blockedByMe && <Pressable style={[styles.button,
              (social.followedByMe || social.requestedByMe) && styles.secondary]}
              onPress={() => void toggleFollow()} disabled={busy}>
              <Text style={[styles.buttonText,
                (social.followedByMe || social.requestedByMe) && styles.secondaryText]}>
                {social.followedByMe ? 'Following' : social.requestedByMe ? 'Requested' : 'Follow pet'}
              </Text>
            </Pressable>}
            {social.privateProfile && !social.followedByMe && !social.requestedByMe &&
              <Text style={styles.note}>This pet approves new followers.</Text>}
            {!social.blockedByMe && <Pressable style={styles.control}
              onPress={() => void updateSocial(() => social.mutedByMe ? unmutePet(petId) : mutePet(petId))}
              disabled={busy}>
              <Text style={styles.controlText}>{social.mutedByMe ? 'Unmute posts' : 'Mute posts'}</Text>
            </Pressable>}
            <Pressable style={styles.control}
              onPress={() => void updateSocial(() => social.blockedByMe ? unblockPet(petId) : blockPet(petId))}
              disabled={busy}>
              <Text style={styles.controlText}>{social.blockedByMe ? 'Unblock pet' : 'Block pet'}</Text>
            </Pressable>
          </>}
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
  privateBadge: { color: colors.green, fontSize: 12, fontWeight: '800', marginTop: 9 },
  bio: { color: colors.ink, fontSize: 15, lineHeight: 23, textAlign: 'center', marginTop: 18 },
  counts: { flexDirection: 'row', gap: 12, marginTop: 16 },
  count: { flex: 1, backgroundColor: colors.card, borderRadius: 18, padding: 18, alignItems: 'center' },
  countNumber: { color: colors.ink, fontSize: 24, fontWeight: '900' },
  countLabel: { color: colors.muted, fontSize: 13, marginTop: 4 },
  button: { minHeight: 54, backgroundColor: colors.accent, borderRadius: 15, marginTop: 20, alignItems: 'center', justifyContent: 'center' },
  secondary: { backgroundColor: colors.greenPale },
  buttonText: { color: 'white', fontSize: 15, fontWeight: '800' },
  secondaryText: { color: colors.green },
  note: { color: colors.muted, textAlign: 'center', lineHeight: 19, marginTop: 12 },
  requests: { color: colors.green, fontWeight: '800', textAlign: 'center', marginTop: 18 },
  control: { alignItems: 'center', paddingVertical: 12, marginTop: 8 },
  controlText: { color: colors.muted, fontWeight: '800' },
  error: { color: '#B23725', textAlign: 'center', marginTop: 16 },
  retry: { color: colors.accent, fontWeight: '800' },
});
