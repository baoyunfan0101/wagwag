import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { DEV_PET_ID, cancelListingOrder, completeListingOrder, getListingOrder, openConversation,
  rateListingSeller, type ListingOrder } from '@/lib/api';
import { formatListingPrice } from '@/lib/listingPrice';
import { colors } from '@/lib/theme';

export default function ListingOrderScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ id: string }>();
  const id = Number(params.id);
  const [order, setOrder] = useState<ListingOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const generation = useRef(0);
  const [confirm, setConfirm] = useState<'cancel' | 'complete' | null>(null);
  const [score, setScore] = useState(5);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    const current = ++generation.current;
    setLoading(true);
    try {
      const result = await getListingOrder(id);
      if (current !== generation.current) return;
      setOrder(result); setScore(result.ratingScore ?? 5); setComment(result.ratingComment ?? ''); setError(null);
    } catch (cause) { if (current === generation.current) setError(cause instanceof Error ? cause.message : 'Could not load order.'); }
    finally { if (current === generation.current) setLoading(false); }
  }, [id]);
  useFocusEffect(useCallback(() => { void load(); return () => { generation.current++; }; }, [load]));

  async function update(action: 'cancel' | 'complete' | 'rate') {
    if (busyRef.current) return;
    busyRef.current = true; setBusy(true); setError(null);
    try {
      const result = action === 'cancel' ? await cancelListingOrder(id) : action === 'complete'
        ? await completeListingOrder(id) : await rateListingSeller(id, score, comment);
      setOrder(result); setConfirm(null);
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not update order.'); }
    finally { busyRef.current = false; setBusy(false); }
  }
  async function contact() {
    if (!order || busyRef.current) return;
    busyRef.current = true; setBusy(true); setError(null);
    try {
      const conversation = await openConversation(order.sellerPetId === DEV_PET_ID ? order.buyerPetId : order.sellerPetId);
      router.push({ pathname: '/conversation/[id]', params: { id: String(conversation.id) } });
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not open messages.'); }
    finally { busyRef.current = false; setBusy(false); }
  }
  return <SafeAreaView style={styles.safe}><ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
    <View style={styles.header}><Pressable onPress={() => router.back()} accessibilityLabel="Go back"><Ionicons name="arrow-back" size={24} color={colors.ink} /></Pressable>
      <Text style={styles.heading}>Order details</Text><View style={{ width: 24 }} /></View>
    {loading && !order && <ActivityIndicator color={colors.accent} />}
    {error && <Pressable onPress={() => { if (!order) void load(); }}><Text style={styles.error}>{error}</Text></Pressable>}
    {order && <>
      <View style={styles.card}>
        <Text style={styles.title}>{order.title}</Text><Text style={styles.price}>{formatListingPrice(order.priceCents)}</Text>
        <Text style={styles.note}>{order.status}</Text><Text style={styles.note}>Seller: {order.sellerName}</Text>
        <Text style={styles.note}>Buyer: {order.buyerName}</Text>
        <Text style={styles.note}>Arrange pickup in messages. Confirm completion only after handing over the item.</Text>
      </View>
      <Pressable style={styles.secondary} disabled={busy} onPress={() => void contact()}><Text style={styles.secondaryText}>Message {order.sellerPetId === DEV_PET_ID ? 'buyer' : 'seller'}</Text></Pressable>
      {order.status === 'RESERVED' && <>
        {confirm ? <View style={styles.card}>
          <Text style={styles.note}>{confirm === 'complete' ? 'Confirm that the buyer has received this item? This marks it sold.' : 'Cancel this reservation? The item becomes available again.'}</Text>
          <Pressable style={styles.primary} disabled={busy} onPress={() => void update(confirm)}>
            {busy ? <ActivityIndicator color="white" /> : <Text style={styles.primaryText}>Confirm {confirm === 'complete' ? 'handoff' : 'cancellation'}</Text>}
          </Pressable><Pressable disabled={busy} onPress={() => setConfirm(null)}><Text style={styles.note}>Keep reservation</Text></Pressable>
        </View> : <>
          {order.sellerPetId === DEV_PET_ID && <Pressable style={styles.primary} disabled={busy} onPress={() => setConfirm('complete')}><Text style={styles.primaryText}>Confirm handoff</Text></Pressable>}
          <Pressable style={styles.secondary} disabled={busy} onPress={() => setConfirm('cancel')}><Text style={styles.secondaryText}>Cancel reservation</Text></Pressable>
        </>}
      </>}
      {order.status === 'COMPLETED' && order.buyerPetId === DEV_PET_ID && <View style={styles.card}>
        <Text style={styles.title}>Rate seller</Text>
        <View style={{ flexDirection: 'row', gap: 10, marginVertical: 18 }}>{[1, 2, 3, 4, 5].map(value =>
          <Pressable key={value} disabled={busy} accessibilityLabel={`${value} stars`} onPress={() => setScore(value)}>
            <Ionicons name={value <= score ? 'star' : 'star-outline'} size={30} color={colors.accent} />
          </Pressable>)}</View>
        <TextInput value={comment} onChangeText={setComment} editable={!busy} maxLength={500} multiline
          placeholder="How was the handoff? (optional)" style={styles.input} />
        <Pressable style={styles.primary} disabled={busy} onPress={() => void update('rate')}>
          {busy ? <ActivityIndicator color="white" /> : <Text style={styles.primaryText}>{order.ratingScore === null ? 'Save rating' : 'Update rating'}</Text>}
        </Pressable>
      </View>}
      {order.ratingScore !== null && order.sellerPetId === DEV_PET_ID && <View style={styles.card}><Text style={styles.note}>Buyer rating: {order.ratingScore} / 5</Text><Text style={styles.note}>{order.ratingComment}</Text></View>}
    </>}
  </ScrollView></SafeAreaView>;
}
const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background }, content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 },
  heading: { color: colors.ink, fontSize: 20, fontWeight: '900' }, title: { color: colors.ink, fontSize: 23, fontWeight: '900' },
  price: { color: colors.accent, fontSize: 22, fontWeight: '800', marginTop: 12 },
  card: { backgroundColor: colors.card, borderRadius: 18, padding: 20, marginTop: 14 }, note: { color: colors.muted, lineHeight: 23, marginTop: 10 },
  primary: { backgroundColor: colors.accent, minHeight: 52, borderRadius: 14, justifyContent: 'center', alignItems: 'center', marginTop: 16 },
  primaryText: { color: 'white', fontWeight: '800', fontSize: 16 }, secondary: { backgroundColor: colors.greenPale, minHeight: 52, borderRadius: 14, justifyContent: 'center', alignItems: 'center', marginTop: 16 },
  secondaryText: { color: colors.green, fontWeight: '800', fontSize: 16 }, error: { color: '#B23725', marginBottom: 12 },
  input: { color: colors.ink, backgroundColor: colors.background, borderRadius: 12, padding: 14, minHeight: 90, textAlignVertical: 'top' },
});
