import * as ImageManipulator from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Image, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { createListing, uploadListingImage } from '@/lib/api';
import { parseListingPrice } from '@/lib/listingPrice';
import { colors } from '@/lib/theme';

export default function NewListingScreen() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('');
  const [photos, setPhotos] = useState<string[]>([]);
  const uploaded = useRef<Record<string, string>>({});
  const [location, setLocation] = useState<{ latitude: number; longitude: number } | null>(null);
  const [choosing, setChoosing] = useState(false);
  const choosingRef = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  async function choosePhotos() {
    if (choosingRef.current || savingRef.current || photos.length >= 4) return;
    choosingRef.current = true;
    setChoosing(true);
    try {
      const selected = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], allowsMultipleSelection: true,
        selectionLimit: 4 - photos.length, orderedSelection: true });
      if (selected.canceled) return;
      const images = await Promise.all(selected.assets.map(async asset => {
        const image = await ImageManipulator.manipulateAsync(asset.uri,
          [{ resize: { width: Math.min(asset.width, 1600) } }], { compress: 0.8, format: ImageManipulator.SaveFormat.JPEG });
        return image.uri;
      }));
      if (mounted.current) setPhotos(previous => [...previous, ...images].slice(0, 4));
    } catch { if (mounted.current) setError('Could not prepare photos.'); }
    finally { choosingRef.current = false; if (mounted.current) setChoosing(false); }
  }

  async function useLocation() {
    if (choosingRef.current || savingRef.current) return;
    choosingRef.current = true;
    setChoosing(true);
    try {
      if ((await Location.requestForegroundPermissionsAsync()).status !== 'granted') throw new Error('Allow location to add a neighborhood.');
      const fix = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      if (mounted.current) setLocation({ latitude: fix.coords.latitude, longitude: fix.coords.longitude });
    } catch (cause) { if (mounted.current) setError(cause instanceof Error ? cause.message : 'Could not get location.'); }
    finally { choosingRef.current = false; if (mounted.current) setChoosing(false); }
  }
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    if (savingRef.current || choosingRef.current) return;
    let priceCents: number;
    try { priceCents = parseListingPrice(price); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Enter a valid price.'); return; }
    if (!title.trim() || !description.trim()) { setError('Add a title and description.'); return; }
    savingRef.current = true;
    setSaving(true);
    setError(null);
    try {
      const imageKeys: string[] = [];
      for (const uri of photos) {
        if (!mounted.current) return;
        const key = uploaded.current[uri] || await uploadListingImage(uri);
        uploaded.current[uri] = key;
        imageKeys.push(key);
      }
      if (!mounted.current) return;
      const listing = await createListing({ title: title.trim(), description: description.trim(), priceCents, imageKeys, ...location });
      if (!mounted.current) return;
      router.replace({ pathname: '/listing/[id]', params: { id: String(listing.id) } });
    } catch (cause) {
      if (mounted.current) setError(cause instanceof Error ? cause.message : 'Could not create the listing.');
    } finally { savingRef.current = false; if (mounted.current) setSaving(false); }
  }

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back"><Ionicons name="arrow-back" size={24} color={colors.ink} /></Pressable>
        <Text style={styles.heading}>New listing</Text><View style={{ width: 24 }} />
      </View>
      <Text style={styles.title}>Pass pet gear forward</Text>
      <Text style={styles.note}>Describe the item clearly so a neighbor knows what they are getting.</Text>
      <Text style={styles.label}>Item name</Text>
      <TextInput style={styles.input} value={title} onChangeText={setTitle} maxLength={120}
        placeholder="e.g. Adjustable dog leash" editable={!saving} />
      <Text style={styles.label}>Description</Text>
      <TextInput style={[styles.input, styles.large]} value={description} onChangeText={setDescription}
        maxLength={2000} multiline textAlignVertical="top" placeholder="Condition, size, and pickup details" editable={!saving} />
      <Text style={styles.label}>Price in USD</Text>
      <TextInput style={styles.input} value={price} onChangeText={setPrice} keyboardType="decimal-pad"
        placeholder="12.00 (or 0 for free)" editable={!saving} />
      <Text style={styles.label}>Photos (optional, up to four)</Text>
      <ScrollView horizontal contentContainerStyle={{ gap: 10 }}>
        {photos.map((uri, index) => <View key={uri}>
          <Image source={{ uri }} style={{ width: 130, height: 130, borderRadius: 12 }} />
          <Pressable disabled={saving || choosing} onPress={() => setPhotos(values => values.filter((_, i) => i !== index))}>
            <Text style={styles.note}>Remove photo</Text>
          </Pressable>
        </View>)}
      </ScrollView>
      <Pressable style={styles.input} disabled={saving || choosing || photos.length >= 4} onPress={() => void choosePhotos()}>
        <Text style={styles.note}>{choosing ? 'Preparing...' : 'Choose photos'}</Text>
      </Pressable>
      <Text style={styles.label}>Pickup neighborhood (optional)</Text>
      <Text style={styles.note}>Only a rounded neighborhood location is stored. Arrange the meeting place in seller messages.</Text>
      {location && <Text style={styles.note}>Approx. {location.latitude.toFixed(2)}, {location.longitude.toFixed(2)}</Text>}
      <Pressable style={styles.input} disabled={saving || choosing} onPress={() => void useLocation()}>
        <Text style={styles.note}>Use my current neighborhood</Text>
      </Pressable>
      {location && <Pressable disabled={saving} onPress={() => setLocation(null)}><Text style={styles.note}>Remove location</Text></Pressable>}
      {error && <Text style={styles.error}>{error}</Text>}
      <Pressable style={[styles.button, saving && styles.disabled]} onPress={() => void save()} disabled={saving || choosing}>
        {saving ? <ActivityIndicator color="white" /> : <Text style={styles.buttonText}>Publish listing</Text>}
      </Pressable>
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 24, paddingBottom: 45 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 },
  heading: { color: colors.ink, fontSize: 20, fontWeight: '900' },
  title: { color: colors.ink, fontSize: 28, fontWeight: '900', marginBottom: 6 },
  note: { color: colors.muted, lineHeight: 21, marginBottom: 10 },
  label: { color: colors.ink, fontWeight: '800', marginTop: 16, marginBottom: 8 },
  input: { backgroundColor: colors.card, borderRadius: 14, borderWidth: 1, borderColor: colors.line,
    padding: 15, color: colors.ink, fontSize: 16 },
  large: { minHeight: 130 },
  links: { minHeight: 90 },
  error: { color: '#B23725', marginTop: 14 },
  button: { backgroundColor: colors.accent, borderRadius: 16, minHeight: 56,
    alignItems: 'center', justifyContent: 'center', marginTop: 25 },
  disabled: { opacity: 0.6 },
  buttonText: { color: 'white', fontWeight: '800', fontSize: 16 },
});
