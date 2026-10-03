import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { createCommunity } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function NewCommunityScreen() {
  const router = useRouter();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    if (saving) return;
    if (!name.trim()) { setError('Give your community a name.'); return; }
    setSaving(true);
    setError(null);
    try {
      const community = await createCommunity(name.trim(), description);
      router.replace({ pathname: '/community/[id]', params: { id: String(community.id) } });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not create the community.');
    } finally {
      setSaving(false);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <View style={styles.header}>
        <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={22} color={colors.ink} />
        </Pressable>
        <Text style={styles.title}>New community</Text><View style={styles.back} />
      </View>
      <Text style={styles.label}>Name</Text>
      <TextInput style={styles.input} value={name} onChangeText={setName}
        placeholder="e.g. Houston Dog Parks" maxLength={80} />
      <Text style={styles.label}>Description</Text>
      <TextInput style={[styles.input, styles.description]} value={description} onChangeText={setDescription}
        placeholder="What brings these pets together?" maxLength={500} multiline textAlignVertical="top" />
      <Text style={styles.note}>You'll join automatically when the community is created.</Text>
      {error && <Text style={styles.error}>{error}</Text>}
      <Pressable style={styles.button} onPress={() => void create()} disabled={saving}>
        {saving ? <ActivityIndicator color="white" /> : <Text style={styles.buttonText}>Create community</Text>}
      </Pressable>
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 42 },
  header: { height: 74, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 22 },
  back: { width: 42, height: 42, borderRadius: 13, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' },
  title: { color: colors.ink, fontSize: 20, fontWeight: '900' },
  label: { color: colors.ink, fontSize: 15, fontWeight: '800', marginBottom: 9, marginTop: 14 },
  input: { backgroundColor: colors.card, borderRadius: 15, padding: 16, color: colors.ink, fontSize: 16 },
  description: { minHeight: 150 },
  note: { color: colors.muted, marginTop: 18, lineHeight: 20 },
  error: { color: '#B23725', marginTop: 18 },
  button: { backgroundColor: colors.accent, borderRadius: 16, minHeight: 56,
    alignItems: 'center', justifyContent: 'center', marginTop: 26 },
  buttonText: { color: 'white', fontSize: 16, fontWeight: '800' },
});
