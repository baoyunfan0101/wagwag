import { StyleSheet, Text, View } from 'react-native';
import type { WalkPoint } from '@/lib/api';
import { colors } from '@/lib/theme';

export type WalkMapProps = { points: WalkPoint[]; followLatest?: boolean };

export default function WalkMap({ points }: WalkMapProps) {
  return <View style={styles.fallback}>
    <Text style={styles.title}>Map preview requires an iOS or Android development build.</Text>
    <Text style={styles.detail}>{points.length} GPS points recorded</Text>
  </View>;
}

const styles = StyleSheet.create({
  fallback: { height: 330, borderRadius: 18, backgroundColor: colors.greenPale,
    alignItems: 'center', justifyContent: 'center', padding: 24 },
  title: { color: colors.ink, fontWeight: '800', textAlign: 'center' },
  detail: { color: colors.muted, marginTop: 10 },
});
