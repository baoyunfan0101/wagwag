import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Image, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { DEV_PET_ID, getPet, type Pet } from '@/lib/api';
import { colors } from '@/lib/theme';

function Detail({ icon, label, value }: { icon: keyof typeof Ionicons.glyphMap; label: string; value: string }) {
  return <View style={styles.detail}>
    <View style={styles.detailIcon}><Ionicons name={icon} size={19} color={colors.green} /></View>
    <Text style={styles.detailLabel}>{label}</Text>
    <Text style={styles.detailValue}>{value}</Text>
  </View>;
}

export default function ProfileScreen() {
  const router = useRouter();
  const [pet, setPet] = useState<Pet | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (refresh = false) => {
    if (refresh) setRefreshing(true);
    else setLoading(true);
    try {
      setPet(await getPet(DEV_PET_ID));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load this profile.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} refreshControl={
      <RefreshControl refreshing={refreshing} onRefresh={() => void load(true)} tintColor={colors.accent} />
    }>
      <View style={styles.topBar}>
        <View style={styles.brand}><View style={styles.brandMark}><Ionicons name="paw" size={18} color="white" /></View><Text style={styles.brandText}>wagwag</Text></View>
        <View style={styles.topActions}>
          <Pressable style={styles.topBadge} onPress={() => router.push('/social')} accessibilityLabel="Find pet friends">
            <Text style={styles.topBadgeText}>FRIENDS</Text>
          </Pressable>
          <Pressable style={styles.topBadge} onPress={() => router.push('/feed')} accessibilityLabel="Open feed">
            <Text style={styles.topBadgeText}>FEED</Text>
            <Ionicons name="arrow-forward" size={14} color={colors.green} />
          </Pressable>
        </View>
      </View>
      {loading && !pet ? <View style={styles.center}><ActivityIndicator size="large" color={colors.accent} /><Text style={styles.muted}>Loading your pet...</Text></View> :
        error && !pet ? <View style={styles.center}>
          <Ionicons name="cloud-offline-outline" size={44} color={colors.accent} />
          <Text style={styles.errorTitle}>Could not load profile</Text><Text style={styles.muted}>{error}</Text>
          <Pressable style={styles.primaryButton} onPress={() => void load()}><Text style={styles.primaryText}>Try again</Text></Pressable>
        </View> : pet ? <>
          <View style={styles.hero}>
            <View style={styles.heroTop}><Text style={styles.eyebrow}>MEET THE STAR</Text><Ionicons name="sparkles" size={20} color="#EAA66F" /></View>
            <View style={styles.avatarWrap}>{pet.avatarUrl ? <Image source={{ uri: pet.avatarUrl }} style={styles.avatar} /> :
              <View style={styles.avatarPlaceholder}><Ionicons name="paw" size={64} color={colors.accent} /></View>}</View>
            <Text style={styles.name}>{pet.name}</Text>
            <Text style={styles.subtitle}>{[pet.breed, pet.species].filter(Boolean).join(' / ')}</Text>
            <View style={styles.heroDivider} />
            <View style={styles.heroBottom}><Ionicons name="heart" size={16} color={colors.accent} /><Text style={styles.heroBottomText}>Every pet has a story worth sharing.</Text></View>
          </View>
          <Text style={styles.sectionTitle}>About {pet.name}</Text>
          <View style={styles.detailsCard}>
            <Detail icon="paw-outline" label="Species" value={pet.species} />
            <Detail icon="ribbon-outline" label="Breed" value={pet.breed || 'Not added yet'} />
            <Detail icon="male-female-outline" label="Gender" value={pet.gender === 'UNKNOWN' ? 'Not specified' : pet.gender === 'MALE' ? 'Male' : 'Female'} />
            <Detail icon="calendar-outline" label="Birthday" value={pet.birthday || 'Not added yet'} />
          </View>
          <Text style={styles.sectionTitle}>A little introduction</Text>
          <View style={styles.bioCard}><Text style={styles.bio}>{pet.bio || 'Tell everyone what makes your pet special.'}</Text></View>
          {error && <Text style={styles.inlineError}>{error}</Text>}
          <Pressable style={styles.primaryButton} onPress={() => router.push('/edit')}>
            <Ionicons name="create-outline" size={19} color="white" /><Text style={styles.primaryText}>Edit profile</Text>
          </Pressable>
          <Pressable style={styles.privacyButton}
            onPress={() => router.push({ pathname: '/pet/[id]', params: { id: String(DEV_PET_ID) } })}>
            <Ionicons name="lock-closed-outline" size={18} color={colors.green} />
            <Text style={styles.privacyText}>Privacy and follow requests</Text>
          </Pressable>
          <Pressable style={styles.privacyButton} onPress={() => router.push('/communities')}>
            <Ionicons name="people-outline" size={18} color={colors.green} />
            <Text style={styles.privacyText}>Explore communities</Text>
          </Pressable>
          <Pressable style={styles.privacyButton} onPress={() => router.push('/walks')}>
            <Ionicons name="walk-outline" size={18} color={colors.green} />
            <Text style={styles.privacyText}>Walks and routes</Text>
          </Pressable>
          <Text style={styles.footer}>Made for the pets who make life better.</Text>
        </> : null}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 42 },
  topBar: { height: 76, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  brandMark: { width: 32, height: 32, borderRadius: 11, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center' },
  brandText: { fontSize: 23, fontWeight: '900', letterSpacing: -1, color: colors.ink },
  topActions: { flexDirection: 'row', gap: 7 },
  topBadge: { flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 11, paddingVertical: 7, borderRadius: 30, backgroundColor: colors.greenPale },
  topBadgeText: { color: colors.green, fontWeight: '800', fontSize: 10, letterSpacing: 1.2 },
  center: { minHeight: 400, alignItems: 'center', justifyContent: 'center', gap: 16 },
  muted: { color: colors.muted, textAlign: 'center', fontSize: 15, lineHeight: 22 },
  errorTitle: { color: colors.ink, fontSize: 23, fontWeight: '800' },
  hero: { backgroundColor: colors.green, borderRadius: 30, padding: 26, alignItems: 'center', overflow: 'hidden' },
  heroTop: { alignSelf: 'stretch', flexDirection: 'row', justifyContent: 'space-between' },
  eyebrow: { color: '#DCE9DF', fontSize: 11, fontWeight: '800', letterSpacing: 2 },
  avatarWrap: { width: 164, height: 164, borderRadius: 82, backgroundColor: '#F3D8BC', borderWidth: 6, borderColor: '#FDF6EC', marginTop: 8, overflow: 'hidden' },
  avatar: { width: '100%', height: '100%' },
  avatarPlaceholder: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#F8D8C0' },
  name: { marginTop: 17, fontSize: 38, fontWeight: '900', letterSpacing: -1.5, color: 'white' },
  subtitle: { marginTop: 3, color: '#DCE9DF', fontSize: 15, fontWeight: '500' },
  heroDivider: { width: '100%', height: 1, backgroundColor: '#6F8C7C', marginTop: 26 },
  heroBottom: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 17 },
  heroBottomText: { color: '#E8F0E9', fontSize: 12 },
  sectionTitle: { color: colors.ink, fontSize: 21, fontWeight: '800', marginTop: 30, marginBottom: 14 },
  detailsCard: { backgroundColor: colors.card, borderRadius: 22, paddingHorizontal: 20, paddingVertical: 6, borderWidth: 1, borderColor: colors.line },
  detail: { flexDirection: 'row', alignItems: 'center', minHeight: 68, borderBottomColor: colors.line, borderBottomWidth: 1, gap: 15 },
  detailIcon: { width: 36, height: 36, borderRadius: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.greenPale },
  detailLabel: { color: colors.muted, fontSize: 14, flex: 1 },
  detailValue: { color: colors.ink, fontSize: 14, fontWeight: '700', textAlign: 'right', flexShrink: 1 },
  bioCard: { backgroundColor: colors.card, borderRadius: 22, padding: 20, borderWidth: 1, borderColor: colors.line },
  bio: { color: colors.ink, lineHeight: 23, fontSize: 15 },
  primaryButton: { backgroundColor: colors.accent, borderRadius: 17, minHeight: 56, flexDirection: 'row', justifyContent: 'center', alignItems: 'center', gap: 9, marginTop: 25, paddingHorizontal: 22 },
  primaryText: { color: 'white', fontSize: 16, fontWeight: '800' },
  privacyButton: { minHeight: 52, borderRadius: 16, backgroundColor: colors.greenPale,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 9, marginTop: 12 },
  privacyText: { color: colors.green, fontSize: 14, fontWeight: '800' },
  inlineError: { color: colors.accent, marginTop: 16 },
  footer: { textAlign: 'center', marginTop: 24, color: '#9AA49C', fontSize: 12 },
});
