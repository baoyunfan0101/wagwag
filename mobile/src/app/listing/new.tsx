import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { createListing } from '@/lib/api';
import { parseListingPrice } from '@/lib/listingPrice';
import { colors } from '@/lib/theme';

export default function NewListingScreen() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('');
  const [imageLinks, setImageLinks] = useState('');
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    if (savingRef.current) return;
    let priceCents: number;
    try { priceCents = parseListingPrice(price); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Enter a valid price.'); return; }
    if (!title.trim() || !description.trim()) { setError('Add a title and description.'); return; }
    const imageUrls = imageLinks.split('\n').map(value => value.trim()).filter(Boolean);
    if (imageUrls.length > 4 || imageUrls.some(value => !value.startsWith('https://'))) {
      setError('Add at most four HTTPS image links, one per line.'); return;
    }
    savingRef.current = true;
    setSaving(true);
    setError(null);
    try {
      const listing = await createListing({ title: title.trim(), description: description.trim(), priceCents, imageUrls });
      router.replace({ pathname: '/listing/[id]', params: { id: String(listing.id) } });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not create the listing.');
    } finally { savingRef.current = false; setSaving(false); }
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
      <Text style={styles.label}>Photo links (optional)</Text>
      <TextInput style={[styles.input, styles.links]} value={imageLinks} onChangeText={setImageLinks}
        maxLength={8200} multiline textAlignVertical="top" autoCapitalize="none" autoCorrect={false}
        placeholder="One HTTPS image URL per line, up to four" editable={!saving} />
      <Text style={styles.note}>Image upload from your phone is planned for a later shopping phase.</Text>
      {error && <Text style={styles.error}>{error}</Text>}
      <Pressable style={[styles.button, saving && styles.disabled]} onPress={() => void save()} disabled={saving}>
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
