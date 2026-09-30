import { Ionicons } from '@expo/vector-icons';
import * as ImageManipulator from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Image, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { DEV_PET_ID, getPet, savePet, uploadAvatar, type Gender, type Pet } from '@/lib/api';
import { colors } from '@/lib/theme';

function Field({ label, value, onChangeText, placeholder, multiline = false, maxLength }:
  { label: string; value: string; onChangeText: (value: string) => void; placeholder: string; multiline?: boolean; maxLength?: number }) {
  return <View style={styles.field}>
    <Text style={styles.label}>{label}</Text>
    <TextInput
      style={[styles.input, multiline && styles.multiline]}
      value={value}
      onChangeText={onChangeText}
      placeholder={placeholder}
      placeholderTextColor="#9BA59D"
      multiline={multiline}
      maxLength={maxLength}
      textAlignVertical={multiline ? 'top' : 'center'}
      autoCapitalize="sentences"
    />
  </View>;
}

export default function EditProfileScreen() {
  const router = useRouter();
  const [pet, setPet] = useState<Pet | null>(null);
  const [name, setName] = useState('');
  const [species, setSpecies] = useState('');
  const [breed, setBreed] = useState('');
  const [gender, setGender] = useState<Gender>('UNKNOWN');
  const [birthday, setBirthday] = useState('');
  const [bio, setBio] = useState('');
  const [image, setImage] = useState<ImagePicker.ImagePickerAsset | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getPet(DEV_PET_ID).then((result) => {
      if (!active) return;
      setPet(result);
      setName(result.name);
      setSpecies(result.species);
      setBreed(result.breed || '');
      setGender(result.gender);
      setBirthday(result.birthday || '');
      setBio(result.bio || '');
    }).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Could not load profile.');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  async function chooseImage() {
    try {
      const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], allowsEditing: true, aspect: [1, 1], quality: 0.8 });
      if (!result.canceled) {
        const selected = result.assets[0];
        const converted = await ImageManipulator.manipulateAsync(
          selected.uri,
          [{ resize: { width: Math.min(selected.width, 1024) } }],
          { compress: 0.8, format: ImageManipulator.SaveFormat.JPEG },
        );
        setImage({ ...selected, uri: converted.uri, mimeType: 'image/jpeg', fileSize: undefined });
        setError(null);
      }
    } catch {
      setError('Could not open the photo library.');
    }
  }

  async function save() {
    if (!pet || saving) return;
    if (!name.trim() || !species.trim()) {
      setError('Name and species are required.');
      return;
    }
    if (birthday && !/^\d{4}-\d{2}-\d{2}$/.test(birthday)) {
      setError('Use YYYY-MM-DD for the birthday.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await savePet(pet.id, {
        name: name.trim(), species: species.trim(), breed: breed.trim() || null,
        gender, birthday: birthday || null, bio: bio.trim() || null,
      });
      if (image) await uploadAvatar(pet.id, image);
      router.back();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not save profile.');
    } finally {
      setSaving(false);
    }
  }

  return <SafeAreaView style={styles.safe}>
    <KeyboardAvoidingView style={styles.fill} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <Pressable style={styles.back} onPress={() => router.back()} accessibilityLabel="Back"><Ionicons name="arrow-back" size={22} color={colors.ink} /></Pressable>
          <Text style={styles.headerTitle}>Edit profile</Text>
          <View style={styles.back} />
        </View>
        {loading ? <View style={styles.center}><ActivityIndicator size="large" color={colors.accent} /></View> : !pet ?
          <View style={styles.center}><Text style={styles.error}>{error || 'Profile not found.'}</Text></View> : <>
            <Text style={styles.title}>Tell their story</Text>
            <Text style={styles.description}>A few details help everyone get to know your pet.</Text>
            <View style={styles.photoCard}>
              <View style={styles.photoWrap}>{image || pet.avatarUrl ?
                <Image source={{ uri: image?.uri || pet.avatarUrl || '' }} style={styles.photo} /> :
                <Ionicons name="paw" size={40} color={colors.accent} />}</View>
              <View style={styles.photoText}><Text style={styles.photoTitle}>Profile photo</Text><Text style={styles.photoHint}>JPEG, PNG, or WebP up to 5 MB</Text></View>
              <Pressable style={styles.photoButton} onPress={() => void chooseImage()} accessibilityLabel="Choose profile photo"><Ionicons name="camera-outline" size={22} color={colors.accent} /></Pressable>
            </View>
            <Field label="Name *" value={name} onChangeText={setName} placeholder="Your pet's name" maxLength={80} />
            <Field label="Species *" value={species} onChangeText={setSpecies} placeholder="Dog, cat, and more" maxLength={80} />
            <Field label="Breed" value={breed} onChangeText={setBreed} placeholder="Optional" maxLength={80} />
            <Text style={styles.label}>Gender</Text>
            <View style={styles.chips}>{(['MALE', 'FEMALE', 'UNKNOWN'] as Gender[]).map((option) =>
              <Pressable key={option} style={[styles.chip, gender === option && styles.chipSelected]} onPress={() => setGender(option)}>
                <Text style={[styles.chipText, gender === option && styles.chipTextSelected]}>{option === 'UNKNOWN' ? 'Unspecified' : option === 'MALE' ? 'Male' : 'Female'}</Text>
              </Pressable>)}</View>
            <Field label="Birthday" value={birthday} onChangeText={setBirthday} placeholder="YYYY-MM-DD" maxLength={10} />
            <Field label="Bio" value={bio} onChangeText={setBio} placeholder="What makes your pet one of a kind?" multiline maxLength={500} />
            <Text style={styles.counter}>{bio.length}/500</Text>
            {error && <Text style={styles.error}>{error}</Text>}
            <Pressable style={[styles.saveButton, saving && styles.disabled]} disabled={saving} onPress={() => void save()}>
              {saving ? <ActivityIndicator color="white" /> : <><Text style={styles.saveText}>Save changes</Text><Ionicons name="arrow-forward" size={19} color="white" /></>}
            </Pressable>
          </>}
      </ScrollView>
    </KeyboardAvoidingView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  fill: { flex: 1 },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', paddingHorizontal: 24, paddingBottom: 48 },
  header: { height: 72, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  back: { width: 42, height: 42, borderRadius: 13, backgroundColor: 'white', alignItems: 'center', justifyContent: 'center' },
  headerTitle: { fontSize: 16, fontWeight: '800', color: colors.ink },
  center: { minHeight: 300, alignItems: 'center', justifyContent: 'center' },
  title: { fontSize: 33, fontWeight: '900', color: colors.ink, letterSpacing: -1.2, marginTop: 15 },
  description: { fontSize: 15, lineHeight: 22, color: colors.muted, marginTop: 6, marginBottom: 24 },
  photoCard: { flexDirection: 'row', alignItems: 'center', backgroundColor: 'white', borderRadius: 22, borderWidth: 1, borderColor: colors.line, padding: 16, marginBottom: 25, gap: 14 },
  photoWrap: { width: 70, height: 70, borderRadius: 19, backgroundColor: colors.accentPale, justifyContent: 'center', alignItems: 'center', overflow: 'hidden' },
  photo: { width: '100%', height: '100%' },
  photoText: { flex: 1 },
  photoTitle: { color: colors.ink, fontSize: 15, fontWeight: '800' },
  photoHint: { color: colors.muted, fontSize: 12, marginTop: 4 },
  photoButton: { width: 40, height: 40, borderRadius: 13, backgroundColor: colors.accentPale, alignItems: 'center', justifyContent: 'center' },
  field: { marginBottom: 19 },
  label: { fontSize: 13, fontWeight: '800', color: colors.ink, marginBottom: 8 },
  input: { backgroundColor: 'white', borderWidth: 1, borderColor: colors.line, borderRadius: 14, paddingHorizontal: 16, minHeight: 54, color: colors.ink, fontSize: 15 },
  multiline: { minHeight: 115, paddingTop: 16 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 21 },
  chip: { borderWidth: 1, borderColor: colors.line, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 11, backgroundColor: 'white' },
  chipSelected: { backgroundColor: colors.green, borderColor: colors.green },
  chipText: { color: colors.muted, fontWeight: '700', fontSize: 13 },
  chipTextSelected: { color: 'white' },
  counter: { alignSelf: 'flex-end', color: colors.muted, fontSize: 12, marginTop: -12 },
  error: { color: '#B23725', marginTop: 15, lineHeight: 20 },
  saveButton: { minHeight: 56, backgroundColor: colors.accent, borderRadius: 16, flexDirection: 'row', gap: 9, justifyContent: 'center', alignItems: 'center', marginTop: 25 },
  disabled: { opacity: 0.65 },
  saveText: { color: 'white', fontSize: 16, fontWeight: '800' },
});
