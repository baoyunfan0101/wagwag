import { Ionicons } from '@expo/vector-icons';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import type { Listing } from '@/lib/api';
import { formatListingPrice } from '@/lib/listingPrice';
import { colors } from '@/lib/theme';

export function ListingCard({ listing, onPress }: { listing: Listing; onPress: () => void }) {
  return <Pressable style={styles.card} onPress={onPress} accessibilityLabel={`View ${listing.title}`}>
    {listing.imageUrls[0] ? <Image source={{ uri: listing.imageUrls[0] }} style={styles.image} /> :
      <View style={[styles.image, styles.empty]}><Ionicons name="paw-outline" size={38} color={colors.green} /></View>}
    <View style={styles.body}>
      <View style={styles.top}><Text style={styles.price}>{formatListingPrice(listing.priceCents)}</Text>
        {listing.favoritedByMe && <Ionicons name="heart" size={18} color={colors.accent} />}</View>
      <Text style={styles.title} numberOfLines={2}>{listing.title}</Text>
      <Text style={styles.seller} numberOfLines={1}>From {listing.sellerName}</Text>
      {listing.status !== 'AVAILABLE' && <Text style={styles.sold}>{listing.status === 'SOLD' ? 'Sold' : 'Reserved'}</Text>}
    </View>
  </Pressable>;
}

const styles = StyleSheet.create({
  card: { flexDirection: 'row', backgroundColor: colors.card, borderRadius: 18, borderWidth: 1,
    borderColor: colors.line, overflow: 'hidden', marginBottom: 12 },
  image: { width: 112, height: 116 },
  empty: { backgroundColor: colors.greenPale, alignItems: 'center', justifyContent: 'center' },
  body: { flex: 1, padding: 12 },
  top: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  price: { color: colors.accent, fontWeight: '900', fontSize: 18 },
  title: { color: colors.ink, fontSize: 16, fontWeight: '800', marginTop: 5 },
  seller: { color: colors.muted, fontSize: 13, marginTop: 5 },
  sold: { color: colors.muted, fontWeight: '800', marginTop: 5 },
});
