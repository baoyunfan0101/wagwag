import assert from 'node:assert/strict';
import test from 'node:test';
import { appendGpsSamples, distanceMeters } from './walkRoute.ts';

const start = '2026-01-01T10:00:00Z';
const timestamp = Date.parse(start);
const sample = (seconds, latitude = 29.76, longitude = -95.37, accuracy = 5) => ({
  timestamp: timestamp + seconds * 1000, coords: { latitude, longitude, accuracy },
});

test('filters inaccurate, stationary, stale and implausible GPS fixes', () => {
  const points = appendGpsSamples([], start, [sample(0), sample(3, 29.76001),
    sample(6, 29.7601, -95.37, 80), sample(9, 30.76), sample(12, 29.7601),
    sample(-1), sample(15, Number.NaN)]);
  assert.equal(points.length, 2);
  assert.equal(points[1].latitude, 29.7601);
  assert.ok(Date.parse(points[1].recordedAt) > Date.parse(points[0].recordedAt));
});

test('background batches are chronological and leave previous points unchanged', () => {
  const original = appendGpsSamples([], start, [sample(0)]);
  const result = appendGpsSamples(original, start, [sample(12, 29.7602), sample(6, 29.7601), sample(0)]);
  assert.equal(original.length, 1);
  assert.equal(result.length, 3);
  assert.deepEqual(result.map(p => p.recordedAt), [start.replace('Z', '.000Z'),
    '2026-01-01T10:00:06.000Z', '2026-01-01T10:00:12.000Z']);
});

test('coordinate bounds, future timestamps and 2000-point cap are enforced', () => {
  assert.equal(appendGpsSamples([], start, [sample(0, 91), sample(0, 0, 181),
    { ...sample(0), timestamp: Date.now() + 60000 }]).length, 0);
  const full = Array.from({ length: 2000 }, () => ({ latitude: 29.76, longitude: -95.37, recordedAt: start }));
  assert.equal(appendGpsSamples(full, start, [sample(100, 29.761)]).length, 2000);
});

test('distance uses meters and handles zero-length routes', () => {
  const origin = { latitude: 0, longitude: 0, recordedAt: start };
  assert.equal(distanceMeters(origin, origin), 0);
  assert.ok(Math.abs(distanceMeters(origin, { ...origin, longitude: 0.001 }) - 111.195) < 0.01);
});
