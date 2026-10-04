import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import * as Location from 'expo-location';
import { createTask, type TaskCategory } from '@/lib/api';
import { colors } from '@/lib/theme';

const categories: { value: TaskCategory; label: string }[] = [
  { value: 'DOG_WALKING', label: 'Dog walking' },
  { value: 'PET_SITTING', label: 'Pet sitting' },
  { value: 'FEEDING', label: 'Feeding' },
  { value: 'CHECK_IN', label: 'Check-in visit' },
];

export default function NewTaskScreen() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState<TaskCategory>('DOG_WALKING');
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [saving, setSaving] = useState(false);
  const [locating, setLocating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function useLocation() {
    setLocating(true);
    setError(null);
    try {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') throw new Error('Location permission is needed to use your position.');
      const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      setLatitude(String(position.coords.latitude));
      setLongitude(String(position.coords.longitude));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not get your location.');
    } finally { setLocating(false); }
  }

  async function save() {
    if (saving) return;
    const lat = Number(latitude);
    const lon = Number(longitude);
    if (!title.trim() || !description.trim()) { setError('Add a title and description.'); return; }
    if (!latitude.trim() || !longitude.trim() || !Number.isFinite(lat) || !Number.isFinite(lon)
      || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
      setError('Choose a valid task location.'); return;
    }
    setSaving(true);
    setError(null);
    try {
      const task = await createTask({ title: title.trim(), description: description.trim(),
        category, latitude: lat, longitude: lon });
      router.replace({ pathname: '/task/[id]', params: { id: String(task.id) } });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not post this task.');
    } finally { setSaving(false); }
  }

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={24} color={colors.ink} />
        </Pressable>
        <Text style={styles.title}>Post a task</Text><View style={{ width: 24 }} />
      </View>
      <Text style={styles.label}>Title</Text>
      <TextInput style={styles.input} value={title} onChangeText={setTitle} maxLength={120}
        placeholder="e.g. Walk my dog" />
      <Text style={styles.label}>Category</Text>
      <View style={styles.categories}>{categories.map(item => <Pressable key={item.value}
        style={[styles.category, category === item.value && styles.selected]} onPress={() => setCategory(item.value)}>
        <Text style={category === item.value ? styles.selectedText : styles.categoryText}>{item.label}</Text>
      </Pressable>)}</View>
      <Text style={styles.label}>What help do you need?</Text>
      <TextInput style={[styles.input, styles.description]} value={description} onChangeText={setDescription}
        maxLength={2000} multiline textAlignVertical="top" placeholder="Describe the visit and any details a helper should know." />
      <Text style={styles.label}>Location</Text>
      <Pressable style={styles.locationButton} onPress={() => void useLocation()} disabled={locating}>
        {locating ? <ActivityIndicator color={colors.green} /> : <Text style={styles.locationText}>Use my current location</Text>}
      </Pressable>
      <View style={styles.coordinates}>
        <TextInput style={[styles.input, styles.coordinate]} value={latitude} onChangeText={setLatitude}
          keyboardType="decimal-pad" placeholder="Latitude" />
        <TextInput style={[styles.input, styles.coordinate]} value={longitude} onChangeText={setLongitude}
          keyboardType="decimal-pad" placeholder="Longitude" />
      </View>
      <Text style={styles.note}>Only an approximate location is public. Exact coordinates are shared with the assigned pet.</Text>
      {error && <Text style={styles.error}>{error}</Text>}
      <Pressable style={styles.button} onPress={() => void save()} disabled={saving}>
        {saving ? <ActivityIndicator color="white" /> : <Text style={styles.buttonText}>Post task</Text>}
      </Pressable>
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 },
  title: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  label: { color: colors.ink, fontWeight: '800', marginTop: 18, marginBottom: 9 },
  input: { backgroundColor: colors.card, borderRadius: 14, padding: 15, color: colors.ink, fontSize: 16 },
  description: { minHeight: 135 },
  categories: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  category: { borderRadius: 14, backgroundColor: colors.card, padding: 12 },
  selected: { backgroundColor: colors.green },
  categoryText: { color: colors.green, fontWeight: '700' },
  selectedText: { color: 'white', fontWeight: '700' },
  locationButton: { backgroundColor: colors.greenPale, borderRadius: 14, padding: 15, alignItems: 'center' },
  locationText: { color: colors.green, fontWeight: '800' },
  coordinates: { flexDirection: 'row', gap: 8, marginTop: 8 },
  coordinate: { flex: 1 },
  note: { color: colors.muted, marginTop: 10, lineHeight: 20 },
  error: { color: '#B23725', marginTop: 15 },
  button: { backgroundColor: colors.accent, borderRadius: 16, minHeight: 56,
    alignItems: 'center', justifyContent: 'center', marginTop: 24 },
  buttonText: { color: 'white', fontWeight: '800', fontSize: 16 },
});
