import * as Crypto from 'expo-crypto';
import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { DEV_PET_ID, favoriteListing, getListing, markListingSold, openConversation,
  unfavoriteListing, reserveListing, type Listing } from '@/lib/api';
import { formatListingPrice } from '@/lib/listingPrice';
import { colors } from '@/lib/theme';

export default function ListingDetailScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ id: string }>();
  const id = Number(params.id);
  const [listing, setListing] = useState<Listing | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const orderId = useRef<string | null>(null);
  const [confirmSold, setConfirmSold] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try { setListing(await getListing(id)); setError(null); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load this listing.'); }
    finally { setLoading(false); }
  }, [id]);
  useFocusEffect(useCallback(() => { void load(); }, [load]));

  async function update(action: 'favorite' | 'unfavorite' | 'sold') {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const result = action === 'favorite' ? await favoriteListing(id)
        : action === 'unfavorite' ? await unfavoriteListing(id) : await markListingSold(id);
      setListing(result);
      setConfirmSold(false);
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not update this listing.'); }
    finally { busyRef.current = false; setBusy(false); }
  }

  async function contact() {
    if (!listing || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const conversation = await openConversation(listing.sellerPetId);
      router.push({ pathname: '/conversation/[id]', params: { id: String(conversation.id) } });
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not contact the seller.'); }
    finally { busyRef.current = false; setBusy(false); }
  }

  async function reserve() {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    orderId.current ??= Crypto.randomUUID();
    try {
      const order = await reserveListing(id, orderId.current);
      orderId.current = null;
      router.push({ pathname: '/listing-order/[id]', params: { id: String(order.id) } });
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not reserve this item.'); }
    finally { busyRef.current = false; setBusy(false); }
  }

  const mine = listing?.sellerPetId === DEV_PET_ID;
  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back"><Ionicons name="arrow-back" size={24} color={colors.ink} /></Pressable>
        <Text style={styles.heading}>Listing details</Text><View style={{ width: 24 }} />
      </View>
      {loading && !listing && <ActivityIndicator color={colors.accent} />}
      {error && <Text style={styles.error}>{error}</Text>}
      {listing && <>
        {listing.imageUrls.length ? <ScrollView horizontal style={styles.gallery} contentContainerStyle={styles.galleryContent}>
          {listing.imageUrls.map((url, index) => <Image key={`${url}-${index}`} source={{ uri: url }} style={styles.image} />)}
        </ScrollView> : <View style={styles.placeholder}><Ionicons name="paw-outline" size={56} color={colors.green} /></View>}
        <View style={styles.card}>
          <View style={styles.row}><Text style={styles.price}>{formatListingPrice(listing.priceCents)}</Text>
            {listing.status !== 'AVAILABLE' && <Text style={styles.sold}>{listing.status}</Text>}</View>
          <Text style={styles.title}>{listing.title}</Text>
          <Text style={styles.seller}>Listed by {listing.sellerName}</Text>
          <Text style={styles.seller}>{listing.sellerAverageRating === null ? 'No seller ratings yet'
            : `${listing.sellerAverageRating.toFixed(1)} / 5 from ${listing.sellerRatingCount} completed handoffs`}</Text>
          {listing.latitude !== null && listing.longitude !== null && <Text style={styles.seller}>
            Pickup neighborhood: {listing.latitude.toFixed(2)}, {listing.longitude.toFixed(2)}</Text>}
          <Text style={styles.description}>{listing.description}</Text>
          <Text style={styles.date}>Posted {new Date(listing.createdAt).toLocaleString()}</Text>
        </View>
        {!mine && (listing.status === 'AVAILABLE' || listing.favoritedByMe) && <Pressable
          style={styles.secondary} disabled={busy} onPress={() => void update(listing.favoritedByMe ? 'unfavorite' : 'favorite')}
          accessibilityLabel={listing.favoritedByMe ? 'Remove favorite' : 'Save favorite'}>
          <Ionicons name={listing.favoritedByMe ? 'heart' : 'heart-outline'} size={20} color={colors.green} />
          <Text style={styles.secondaryText}>{listing.favoritedByMe ? 'Saved' : 'Save listing'}</Text>
        </Pressable>}
        {!mine && listing.status === 'AVAILABLE' && <Pressable style={styles.primary} disabled={busy} onPress={() => void contact()}>
          {busy ? <ActivityIndicator color="white" /> : <><Ionicons name="chatbubble-outline" size={19} color="white" />
            <Text style={styles.primaryText}>Contact seller</Text></>}
        </Pressable>}
        {!mine && listing.status === 'AVAILABLE' && <Pressable style={styles.secondary} disabled={busy} onPress={() => void reserve()}>
          <Text style={styles.secondaryText}>Reserve item</Text>
        </Pressable>}
        {listing.myActiveOrderId && <Pressable style={styles.primary}
          onPress={() => router.push({ pathname: '/listing-order/[id]', params: { id: String(listing.myActiveOrderId) } })}>
          <Text style={styles.primaryText}>Open reserved order</Text>
        </Pressable>}
        {mine && listing.status === 'AVAILABLE' && (confirmSold ? <View style={styles.confirm}>
          <Text style={styles.note}>Mark this item sold? It will leave the Browse list.</Text>
          <Pressable style={styles.primary} disabled={busy} onPress={() => void update('sold')}>
            {busy ? <ActivityIndicator color="white" /> : <Text style={styles.primaryText}>Confirm sold</Text>}
          </Pressable>
          <Pressable onPress={() => setConfirmSold(false)}><Text style={styles.secondaryText}>Keep available</Text></Pressable>
        </View> : <Pressable style={styles.secondary} onPress={() => setConfirmSold(true)}>
          <Ionicons name="checkmark-circle-outline" size={20} color={colors.green} />
          <Text style={styles.secondaryText}>Mark sold</Text>
        </Pressable>)}
      </>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 20, paddingBottom: 45 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 18 },
  heading: { color: colors.ink, fontSize: 20, fontWeight: '900' },
  gallery: { marginBottom: 15 },
  galleryContent: { gap: 8 },
  image: { width: 260, height: 225, borderRadius: 18, backgroundColor: colors.greenPale },
  placeholder: { height: 200, borderRadius: 18, backgroundColor: colors.greenPale,
    alignItems: 'center', justifyContent: 'center', marginBottom: 15 },
  card: { backgroundColor: colors.card, borderRadius: 20, borderWidth: 1, borderColor: colors.line, padding: 20 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  price: { color: colors.accent, fontSize: 26, fontWeight: '900' },
  sold: { backgroundColor: colors.greenPale, color: colors.green, fontWeight: '900', padding: 8, borderRadius: 9 },
  title: { color: colors.ink, fontSize: 25, fontWeight: '900', marginTop: 12 },
  seller: { color: colors.muted, marginTop: 8 },
  description: { color: colors.ink, lineHeight: 23, marginTop: 22 },
  date: { color: colors.muted, marginTop: 20, fontSize: 12 },
  primary: { backgroundColor: colors.accent, borderRadius: 15, minHeight: 54, flexDirection: 'row', gap: 8,
    alignItems: 'center', justifyContent: 'center', marginTop: 16 },
  primaryText: { color: 'white', fontSize: 16, fontWeight: '800' },
  secondary: { backgroundColor: colors.greenPale, borderRadius: 15, minHeight: 52, flexDirection: 'row', gap: 8,
    alignItems: 'center', justifyContent: 'center', marginTop: 14 },
  secondaryText: { color: colors.green, fontSize: 15, fontWeight: '800', textAlign: 'center' },
  confirm: { marginTop: 16, alignItems: 'center' },
  note: { color: colors.muted, textAlign: 'center' },
  error: { color: '#B23725', lineHeight: 20, marginBottom: 12 },
});
