import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getNotifications, readNotification, type PetNotification } from '@/lib/api';
import { mergeNotifications } from '@/lib/messageState';
import { colors } from '@/lib/theme';

export default function NotificationsScreen() {
  const router = useRouter();
  const [items, setItems] = useState<PetNotification[]>([]);
  const [nextBeforeId, setNextBeforeId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [openingId, setOpeningId] = useState<number | null>(null);
  const opening = useRef(false);
  const generation = useRef(0);
  const moreBusy = useRef(false);

  const load = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    setNextBeforeId(null);
    setMoreError(null);
    if (refresh) setRefreshing(true);
    else setLoading(true);
    try {
      const result = await getNotifications();
      if (current !== generation.current) return;
      setItems(existing => mergeNotifications(existing, result.items).filter(item => result.items.some(fresh => fresh.id === item.id)));
      setNextBeforeId(result.nextBeforeId);
      setError(null);
    } catch {
      if (current === generation.current) setError('Could not load notifications. Try again.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, []);

  useFocusEffect(useCallback(() => { void load(); return () => { generation.current++; }; }, [load]));

  async function loadMore(retry = false) {
    if (nextBeforeId === null || moreBusy.current || loading || refreshing || (moreError && !retry)) return;
    moreBusy.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const result = await getNotifications(20, nextBeforeId);
      if (current !== generation.current) return;
      setItems(existing => mergeNotifications(existing, result.items));
      setNextBeforeId(result.nextBeforeId);
    } catch {
      if (current === generation.current) setMoreError('Could not load more notifications. Try again.');
    } finally { moreBusy.current = false; setLoadingMore(false); }
  }

  async function open(item: PetNotification) {
    if (opening.current) return;
    opening.current = true;
    setOpeningId(item.id);
    const current = generation.current;
    try {
      const read = await readNotification(item.id);
      if (current !== generation.current) return;
      setItems(existing => mergeNotifications(existing, [read]));
      setError(null);
      if (item.type === 'MESSAGE') router.push({ pathname: '/conversation/[id]', params: { id: String(item.targetId) } });
      else router.push({ pathname: '/task/[id]', params: { id: String(item.targetId) } });
    } catch {
      if (current === generation.current) setError('Could not open this notification. Try again.');
    } finally { opening.current = false; setOpeningId(null); }
  }

  return <SafeAreaView style={styles.safe}>
    <FlatList data={items} keyExtractor={item => String(item.id)} contentContainerStyle={styles.content}
      refreshing={refreshing} onRefresh={() => void load(true)}
      onEndReached={() => void loadMore()} onEndReachedThreshold={0.5}
      ListHeaderComponent={<>
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
            <Ionicons name="arrow-back" size={24} color={colors.ink} />
          </Pressable>
          <Text style={styles.title}>Notifications</Text><View style={{ width: 24 }} />
        </View>
        {loading && <ActivityIndicator color={colors.accent} />}
        {error && <Pressable onPress={() => void load()}><Text style={styles.error}>{error}</Text></Pressable>}
      </>}
      renderItem={({ item }) => <Pressable style={[styles.card, !item.readAt && styles.unread]}
        onPress={() => void open(item)} disabled={openingId !== null}>
        <Ionicons name={item.type === 'MESSAGE' ? 'chatbubble-outline' : 'briefcase-outline'} size={24} color={colors.green} />
        <View style={styles.body}>
          <Text style={styles.name}>{item.type === 'MESSAGE' ? `Message from ${item.actorName}` : item.taskTitle}</Text>
          <Text style={styles.note}>{item.type === 'MESSAGE' ? 'Open conversation' :
            `${item.actorName}: ${item.taskStatus?.replaceAll('_', ' ')}`}</Text>
          <Text style={styles.date}>{new Date(item.createdAt).toLocaleString()}</Text>
          {!item.readAt && <Text style={styles.badge}>Unread</Text>}
        </View>
        {openingId === item.id && <ActivityIndicator color={colors.accent} />}
      </Pressable>}
      ListEmptyComponent={!loading && !error ? <Text style={styles.note}>You are all caught up.</Text> : null}
      ListFooterComponent={loadingMore ? <ActivityIndicator color={colors.accent} /> : moreError ?
        <Pressable onPress={() => void loadMore(true)}><Text style={styles.error}>{moreError}</Text></Pressable> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 22 },
  title: { color: colors.ink, fontSize: 24, fontWeight: '900' },
  card: { flexDirection: 'row', gap: 14, padding: 18, borderRadius: 18, backgroundColor: colors.card,
    borderWidth: 1, borderColor: colors.line, marginBottom: 10 },
  unread: { borderColor: colors.green },
  body: { flex: 1 },
  name: { color: colors.ink, fontSize: 16, fontWeight: '800' },
  note: { color: colors.muted, lineHeight: 20, marginTop: 6 },
  date: { color: colors.muted, fontSize: 11, marginTop: 7 },
  badge: { color: colors.green, fontWeight: '800', fontSize: 11, marginTop: 6 },
  error: { color: '#B23725', marginVertical: 12 },
});
