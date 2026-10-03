import Mapbox, { Camera, CircleLayer, LineLayer, MapView, ShapeSource } from '@rnmapbox/maps';
import { StyleSheet, Text, View } from 'react-native';
import type { WalkMapProps } from './WalkMap';
import { colors } from '@/lib/theme';

const token = process.env.EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN;
if (token && token.startsWith('pk.')) void Mapbox.setAccessToken(token);

export default function WalkMap({ points, followLatest = false }: WalkMapProps) {
  if (!token || !token.startsWith('pk.')) {
    return <View style={styles.fallback}>
      <Text style={styles.message}>Set EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN to show the route map.</Text>
    </View>;
  }

  const coordinates: [number, number][] = points.map((point) => [point.longitude, point.latitude]);
  const latest: [number, number] = coordinates.at(-1) ?? [-95.3698, 29.7604];
  const longitudes = coordinates.map((point) => point[0]);
  const latitudes = coordinates.map((point) => point[1]);
  const hasBounds = !followLatest && coordinates.length > 1 &&
    (Math.max(...longitudes) !== Math.min(...longitudes) || Math.max(...latitudes) !== Math.min(...latitudes));
  const bounds = hasBounds ? {
    ne: [Math.max(...longitudes), Math.max(...latitudes)] as [number, number],
    sw: [Math.min(...longitudes), Math.min(...latitudes)] as [number, number],
  } : undefined;

  return <View style={styles.frame}>
    <MapView style={styles.map} styleURL="mapbox://styles/mapbox/outdoors-v12">
      <Camera centerCoordinate={bounds ? undefined : latest} bounds={bounds}
        zoomLevel={bounds ? undefined : 14} animationDuration={followLatest ? 500 : 0} />
      {coordinates.length > 1 && <ShapeSource id="walk-route" shape={{ type: 'LineString', coordinates }}>
        <LineLayer id="walk-route-line" style={{ lineColor: colors.accent, lineWidth: 5, lineCap: 'round' }} />
      </ShapeSource>}
      {coordinates.length > 0 && <ShapeSource id="walk-position" shape={{ type: 'Point', coordinates: latest }}>
        <CircleLayer id="walk-position-dot" style={{ circleColor: colors.green, circleRadius: 7,
          circleStrokeColor: '#FFFFFF', circleStrokeWidth: 2 }} />
      </ShapeSource>}
    </MapView>
  </View>;
}

const styles = StyleSheet.create({
  frame: { height: 330, borderRadius: 18, overflow: 'hidden' },
  map: { flex: 1 },
  fallback: { height: 330, borderRadius: 18, backgroundColor: colors.greenPale,
    alignItems: 'center', justifyContent: 'center', padding: 24 },
  message: { color: colors.ink, textAlign: 'center', fontWeight: '700' },
});
