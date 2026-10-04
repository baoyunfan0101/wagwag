import assert from 'node:assert/strict';
import test from 'node:test';
import { MAX_POST_VIDEO_BYTES, validateVideoSize, videoContentType } from './postVideo.ts';

test('video selection uses allowed MIME types or a supported extension when metadata is absent', () => {
  assert.equal(videoContentType({ uri: 'blob:test', mimeType: 'video/mp4' }), 'video/mp4');
  assert.equal(videoContentType({ uri: 'file:///video', fileName: 'Pet.MOV' }), 'video/quicktime');
  assert.equal(videoContentType({ uri: 'file:///pet.webm?x=1' }), 'video/webm');
  assert.throws(() => videoContentType({ uri: 'blob:test' }));
  assert.throws(() => videoContentType({ uri: 'file:///fake.mp4', mimeType: 'application/pdf' }));
});

test('actual upload bytes must be non-empty and at most 50 MB', () => {
  validateVideoSize(1);
  validateVideoSize(MAX_POST_VIDEO_BYTES);
  for (const size of [0, -1, NaN, Infinity, MAX_POST_VIDEO_BYTES + 1]) assert.throws(() => validateVideoSize(size));
});
