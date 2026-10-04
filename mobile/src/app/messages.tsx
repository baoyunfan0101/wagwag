import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getConversations, type Conversation } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function MessagesScreen() {
  const router = useRouter();
  const [items, setItems] = useState<Conversation[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  const generation = useRef(0);
  const moreBusy = useRef(false);

  const load = useCallback(async (refresh = false) => {
    const current = ++generation.current;
    setNextPage(null);
    setMoreError(null);
    if (refresh) setRefreshing(true);
    else setLoading(true);
    try {
      const result = await getConversations();
      if (current !== generation.current) return;
      setItems(result.items);
      setNextPage(result.nextPage);
      setError(null);
    } catch {
      if (current === generation.current) setError('Could not load conversations. Try again.');
    } finally {
      if (current === generation.current) { setLoading(false); setRefreshing(false); }
    }
  }, []);

  useFocusEffect(useCallback(() => { void load(); return () => { generation.current++; }; }, [load]));

  async function loadMore(retry = false) {
    if (nextPage === null || moreBusy.current || loading || refreshing || (moreError && !retry)) return;
    moreBusy.current = true;
    const current = generation.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const result = await getConversations(20, nextPage);
      if (current !== generation.current) return;
      setItems(existing => {
        const ids = new Set(existing.map(item => item.id));
        return [...existing, ...result.items.filter(item => !ids.has(item.id))];
      });
      setNextPage(result.nextPage);
    } catch {
      if (current === generation.current) setMoreError('Could not load more conversations. Try again.');
    } finally { moreBusy.current = false; setLoadingMore(false); }
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
          <Text style={styles.title}>Messages</Text>
          <Pressable onPress={() => router.push('/social')} accessibilityLabel="Find a pet to message">
            <Ionicons name="create-outline" size={24} color={colors.green} />
          </Pressable>
        </View>
        <Text style={styles.note}>Open a pet profile to start a conversation.</Text>
        {loading && <ActivityIndicator color={colors.accent} />}
        {error && <Pressable onPress={() => void load()}><Text style={styles.error}>{error}</Text></Pressable>}
      </>}
      renderItem={({ item }) => <Pressable style={styles.card}
        onPress={() => router.push({ pathname: '/conversation/[id]', params: { id: String(item.id) } })}>
        {item.petAvatarUrl ? <Image source={{ uri: item.petAvatarUrl }} style={styles.avatar} /> :
          <View style={[styles.avatar, styles.placeholder]}><Ionicons name="paw" size={22} color={colors.green} /></View>}
        <View style={styles.body}>
          <Text style={styles.name}>{item.petName}</Text>
          <Text style={styles.preview} numberOfLines={2}>{item.lastMessage || 'Say hello!'}</Text>
          {!item.canMessage && <Text style={styles.preview}>Messaging unavailable</Text>}
        </View>
        <Ionicons name="chevron-forward" size={18} color={colors.muted} />
      </Pressable>}
      ListEmptyComponent={!loading && !error ? <Text style={styles.note}>No conversations yet.</Text> : null}
      ListFooterComponent={loadingMore ? <ActivityIndicator color={colors.accent} /> : moreError ?
        <Pressable onPress={() => void loadMore(true)}><Text style={styles.error}>{moreError}</Text></Pressable> : null}
    />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 18 },
  title: { color: colors.ink, fontSize: 24, fontWeight: '900' },
  note: { color: colors.muted, lineHeight: 21, marginBottom: 18 },
  card: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 18, borderRadius: 18,
    backgroundColor: colors.card, marginBottom: 10, borderWidth: 1, borderColor: colors.line },
  avatar: { width: 48, height: 48, borderRadius: 24 },
  placeholder: { backgroundColor: colors.greenPale, alignItems: 'center', justifyContent: 'center' },
  body: { flex: 1 },
  name: { color: colors.ink, fontSize: 17, fontWeight: '800' },
  preview: { color: colors.muted, marginTop: 5, lineHeight: 20 },
  error: { color: '#B23725', marginVertical: 12 },
});
