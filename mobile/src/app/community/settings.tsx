import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getCommunity, updateCommunity } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function CommunitySettingsScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const communityId = Number(id);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [rules, setRules] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getCommunity(communityId).then((community) => {
      if (!active) return;
      setName(community.name);
      setDescription(community.description || '');
      setRules(community.rules || '');
    }).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Could not load community.');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [communityId]);

  async function save() {
    if (saving) return;
    setSaving(true);
    setError(null);
    try {
      await updateCommunity(communityId, description, rules);
      router.back();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not save community.');
    } finally { setSaving(false); }
  }

  return <SafeAreaView style={styles.safe}>
    <View style={styles.content}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back"><Ionicons name="arrow-back" size={24} color={colors.ink} /></Pressable>
        <Text style={styles.title}>Manage community</Text><View style={{ width: 24 }} />
      </View>
      {loading ? <ActivityIndicator color={colors.accent} /> : <>
        <Text style={styles.name}>{name}</Text>
        <Text style={styles.label}>Description</Text>
        <TextInput style={styles.input} value={description} onChangeText={setDescription}
          maxLength={500} multiline placeholder="What is this community about?" />
        <Text style={styles.label}>Rules</Text>
        <TextInput style={[styles.input, styles.rules]} value={rules} onChangeText={setRules}
          maxLength={2000} multiline textAlignVertical="top" placeholder="How should members participate?" />
        {error && <Text style={styles.error}>{error}</Text>}
        <Pressable style={styles.save} onPress={() => void save()} disabled={saving}>
          {saving ? <ActivityIndicator color="white" /> : <Text style={styles.saveText}>Save changes</Text>}
        </Pressable>
      </>}
    </View>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 },
  title: { color: colors.ink, fontSize: 20, fontWeight: '900' },
  name: { color: colors.ink, fontSize: 22, fontWeight: '800', marginBottom: 20 },
  label: { color: colors.ink, fontWeight: '800', marginBottom: 8 },
  input: { backgroundColor: colors.card, borderRadius: 14, padding: 14, color: colors.ink, minHeight: 80, marginBottom: 20 },
  rules: { minHeight: 150 },
  error: { color: '#B23725', marginBottom: 16 },
  save: { backgroundColor: colors.accent, minHeight: 48, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
  saveText: { color: 'white', fontWeight: '800' },
});
