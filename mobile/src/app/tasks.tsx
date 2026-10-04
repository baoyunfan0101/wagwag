import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getTasks, getTaskProfile, setTaskAvailability, type PetTask, type TaskProfile } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function TasksScreen() {
  const router = useRouter();
  const [scope, setScope] = useState<'open' | 'mine'>('open');
  const [tasks, setTasks] = useState<PetTask[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [profile, setProfile] = useState<TaskProfile | null>(null);
  const [availabilityBusy, setAvailabilityBusy] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);
  const generation = useRef(0);
  const moreInFlight = useRef(false);
  const profileVersion = useRef(0);

  const loadFirst = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    const profileReadVersion = profileVersion.current;
    setNextPage(null);
    setMoreError(null);
    if (refresh) setRefreshing(true);
    else { setLoading(true); setTasks([]); }
    try {
      const [result, currentProfile] = await Promise.all([getTasks(scope), getTaskProfile()]);
      if (current !== generation.current) return;
      setTasks(result.items);
      if (profileReadVersion === profileVersion.current) setProfile(currentProfile);
      setNextPage(result.nextPage);
      setError(null);
    } catch (cause) {
      if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load tasks.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, [scope]);

  useFocusEffect(useCallback(() => { void loadFirst(); return () => { generation.current++; }; }, [loadFirst]));

  async function updateAvailability(value: boolean) {
    if (availabilityBusy) return;
    setAvailabilityBusy(true);
    profileVersion.current++;
    setProfileError(null);
    try { setProfile(await setTaskAvailability(value)); }
    catch (cause) { setProfileError(cause instanceof Error ? cause.message : 'Could not update availability.'); }
    finally { profileVersion.current++; setAvailabilityBusy(false); }
  }

  async function loadMore(retry = false) {
    if (nextPage === null || moreInFlight.current || loading || refreshing || (moreError && !retry)) return;
    moreInFlight.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const result = await getTasks(scope, 20, nextPage);
      if (current !== generation.current) return;
      setTasks(existing => {
        const ids = new Set(existing.map(task => task.id));
        return [...existing, ...result.items.filter(task => !ids.has(task.id))];
      });
      setNextPage(result.nextPage);
    } catch (cause) {
      if (current === generation.current) setMoreError(cause instanceof Error ? cause.message : 'Could not load more tasks.');
    } finally { moreInFlight.current = false; setLoadingMore(false); }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={tasks} keyExtractor={task => String(task.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void loadFirst(true)}
      onEndReached={() => void loadMore()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={24} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Pet-care tasks</Text>
          <Pressable onPress={() => router.push('/task/new')} accessibilityLabel="Post task">
            <Ionicons name="add-circle" size={30} color={colors.accent} />
          </Pressable>
        </View>
        {profile && <View style={styles.profile}>
          <View style={styles.availability}>
            <Text style={[styles.cardTitle, styles.availabilityText]}>Available to accept tasks</Text>
            <Switch value={profile.acceptingTasks} disabled={availabilityBusy || loading || refreshing}
              onValueChange={value => void updateAvailability(value)} trackColor={{ true: colors.green }} />
          </View>
          <Text style={styles.note}>{profile.averageRating === null ? 'No ratings yet' :
            `${profile.averageRating.toFixed(1)} / 5 from ${profile.ratingCount} ratings`}</Text>
        </View>}
        {profileError && <Text style={styles.error}>{profileError}</Text>}
        <Pressable style={styles.nearby} onPress={() => router.push('/task/nearby')}>
          <Ionicons name="location-outline" size={19} color={colors.green} />
          <Text style={styles.tabText}>Find tasks near me</Text>
        </Pressable>
        <View style={styles.tabs}>
          {(['open', 'mine'] as const).map(value => <Pressable key={value}
            style={[styles.tab, scope === value && styles.activeTab]} onPress={() => setScope(value)}>
            <Text style={[styles.tabText, scope === value && styles.activeTabText]}>
              {value === 'open' ? 'Available' : 'My tasks'}
            </Text>
          </Pressable>)}
        </View>
        {loading && <ActivityIndicator color={colors.accent} />}
        {error && <Pressable onPress={() => void loadFirst()}><Text style={styles.error}>{error} Try again</Text></Pressable>}
      </>}
      renderItem={({ item }) => <Pressable style={styles.card}
        onPress={() => router.push({ pathname: '/task/[id]', params: { id: String(item.id) } })}>
        <Text style={styles.cardTitle}>{item.title}</Text>
        <Text style={styles.note}>{item.category.replaceAll('_', ' ')} | {item.status.replaceAll('_', ' ')}</Text>
        <Text style={styles.note}>Posted by {item.creatorName}</Text>
      </Pressable>}
      ListEmptyComponent={!loading && !error ? <Text style={styles.note}>No tasks here yet.</Text> : null}
      ListFooterComponent={loadingMore ? <ActivityIndicator color={colors.accent} /> : moreError ?
        <Pressable onPress={() => void loadMore(true)}><Text style={styles.error}>{moreError} Try again</Text></Pressable> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  tabs: { flexDirection: 'row', gap: 8, marginBottom: 18 },
  tab: { flex: 1, padding: 12, borderRadius: 14, backgroundColor: colors.card, alignItems: 'center' },
  activeTab: { backgroundColor: colors.green },
  tabText: { color: colors.green, fontWeight: '800' },
  activeTabText: { color: 'white' },
  profile: { backgroundColor: colors.card, borderRadius: 16, padding: 16, marginBottom: 12 },
  availability: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10 },
  availabilityText: { flex: 1 },
  nearby: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    backgroundColor: colors.greenPale, borderRadius: 14, padding: 14, marginBottom: 16 },
  card: { backgroundColor: colors.card, borderRadius: 16, padding: 18, marginBottom: 10,
    borderWidth: 1, borderColor: colors.line },
  cardTitle: { color: colors.ink, fontWeight: '800', fontSize: 17 },
  note: { color: colors.muted, marginTop: 7, lineHeight: 20 },
  error: { color: '#B23725', marginVertical: 12 },
});
