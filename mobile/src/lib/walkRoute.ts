import type { WalkPoint } from './api';

export type GpsSample = {
  timestamp: number;
  coords: { latitude: number; longitude: number; accuracy: number | null };
};

export function distanceMeters(first: WalkPoint, second: WalkPoint): number {
  const radians = Math.PI / 180;
  const latitude = (second.latitude - first.latitude) * radians;
  const longitude = (second.longitude - first.longitude) * radians;
  const a = Math.sin(latitude / 2) ** 2 + Math.cos(first.latitude * radians)
    * Math.cos(second.latitude * radians) * Math.sin(longitude / 2) ** 2;
  return 6371008.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
}

export function appendGpsSamples(points: WalkPoint[], startedAt: string, samples: GpsSample[]): WalkPoint[] {
  const result = [...points];
  for (const sample of [...samples].sort((a, b) => a.timestamp - b.timestamp)) {
    if (result.length >= 2000) break;
    const { latitude, longitude, accuracy } = sample.coords;
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || !Number.isFinite(sample.timestamp)
      || Math.abs(latitude) > 90 || Math.abs(longitude) > 180
      || (accuracy !== null && (!Number.isFinite(accuracy) || accuracy < 0 || accuracy > 35))
      || sample.timestamp < Date.parse(startedAt) || sample.timestamp > Date.now() + 5000) continue;
    const point = { latitude, longitude, recordedAt: new Date(sample.timestamp).toISOString() };
    const previous = result.at(-1);
    if (previous) {
      const seconds = (sample.timestamp - Date.parse(previous.recordedAt)) / 1000;
      const distance = distanceMeters(previous, point);
      if (seconds <= 0 || distance < 5 || distance / seconds > 12) continue;
    }
    result.push(point);
  }
  return result;
}
