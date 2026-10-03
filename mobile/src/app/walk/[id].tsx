import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import WalkMap from '@/components/WalkMap';
import { getWalk, type Walk } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function WalkDetailScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const [walk, setWalk] = useState<Walk | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getWalk(Number(id)).then((result) => {
      if (active) { setWalk(result); setError(null); }
    }).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Could not load this walk.');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [id]);

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={24} color={colors.ink} />
        </Pressable>
        <Text style={styles.title}>Walk route</Text><View style={{ width: 24 }} />
      </View>
      {loading ? <ActivityIndicator color={colors.accent} /> : error ?
        <Text style={styles.error}>{error}</Text> : walk ? <>
          <WalkMap points={walk.points} route={walk.route} />
          <View style={styles.card}>
            <Text style={styles.date}>{new Date(walk.startedAt).toLocaleString()}</Text>
            <Text style={styles.meta}>Ended {new Date(walk.endedAt).toLocaleTimeString()}</Text>
            <Text style={styles.meta}>{walk.points.length} GPS points saved</Text>
            <Text style={styles.meta}>{(walk.distanceMeters / 1000).toFixed(2)} km walked</Text>
          </View>
        </> : null}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  card: { backgroundColor: colors.card, borderRadius: 17, padding: 20, marginTop: 18 },
  date: { color: colors.ink, fontSize: 18, fontWeight: '800' },
  meta: { color: colors.muted, marginTop: 8 },
  error: { color: '#B23725' },
});
