import assert from 'node:assert/strict';
import test from 'node:test';
import { readyVideo } from './videoUploadFlow.ts';

const ticket = { id: 'upload-1', key: 'pets/1/video.mp4', uploadUrl: 'https://store.test/put', headers: { 'If-None-Match': '*' } };
const state = (status, error = null) => ({ id: ticket.id, key: ticket.key, status, error, videoUrl: null, thumbnailUrl: null });
function setup(overrides = {}) {
  const calls = [];
  const api = {
    prepare: async () => { calls.push('prepare'); return ticket; },
    renew: async () => { calls.push('renew'); return ticket; },
    get: async () => { calls.push('get'); return state('READY'); },
    complete: async () => { calls.push('complete'); return state('QUEUED'); },
    retry: async () => { calls.push('retry'); return state('QUEUED'); },
    put: async (value, progress) => { calls.push('put'); assert.equal(value, ticket); progress(45); progress(100); },
    missingSource: error => error.message === 'missing',
    wait: async () => { calls.push('wait'); },
    ...overrides,
  };
  return { calls, api, draft: { uploaded: false }, progress: [], signal: new AbortController().signal };
}
async function run(f) { return readyVideo(f.draft, f.api, value => f.progress.push(value), f.signal); }

test('upload progress precedes processing and a ready key can be published', async () => {
  const f = setup();
  assert.equal(await run(f), ticket.key);
  assert.deepEqual(f.calls, ['prepare', 'put', 'complete', 'wait', 'get']);
  assert.deepEqual(f.progress.map(p => p.stage), ['uploading', 'uploading', 'uploading', 'queued', 'ready']);
  assert.equal(f.progress[1].percent, 45);
  assert.equal(f.draft.uploaded, true);
});

test('retry after losing the PUT response probes completion without another PUT or ticket', async () => {
  const f = setup({ put: async () => { throw new Error('connection dropped'); } });
  assert.equal(await run(f), ticket.key);
  assert.equal(f.draft.uploaded, true);
  assert.deepEqual(f.calls, ['prepare', 'complete', 'wait', 'get']);
});

test('failed upload keeps the ticket; next attempt renews the same key only if source is absent', async () => {
  const f = setup({
    get: async () => state('UPLOADING'),
    complete: async () => { throw new Error('missing'); },
    put: async () => { throw new Error('offline'); },
  });
  await assert.rejects(run(f), /offline/);
  assert.equal(f.draft.ticket, ticket);
  f.api.put = async () => f.calls.push('put');
  let completions = 0;
  f.api.complete = async () => { if (++completions === 1) throw new Error('missing'); return state('READY'); };
  assert.equal(await run(f), ticket.key);
  assert.deepEqual(f.calls, ['prepare', 'renew', 'put']);
});

test('processing or publish retries retain uploaded bytes and do not repeat PUT', async () => {
  const f = setup({ get: async () => { throw new Error('poll offline'); } });
  await assert.rejects(run(f), /poll offline/);
  f.api.get = async () => state('READY');
  assert.equal(await run(f), ticket.key);
  assert.equal(f.calls.filter(c => c === 'put').length, 1);
  assert.equal(f.calls.filter(c => c === 'prepare').length, 1);
});

test('completed upload with a failed complete response is not uploaded again', async () => {
  const f = setup({ complete: async () => { throw new Error('API offline'); } });
  await assert.rejects(run(f), /API offline/);
  f.api.get = async () => state('UPLOADING');
  f.api.complete = async () => state('READY');
  assert.equal(await run(f), ticket.key);
  assert.equal(f.calls.filter(c => c === 'put').length, 1);
  assert.equal(f.calls.includes('renew'), false);
});

test('a transient processing failure retries the job, while invalid video requires a new file', async () => {
  const f = setup();
  f.draft = { ticket, uploaded: true };
  let reads = 0;
  f.api.get = async () => state(++reads === 1 ? 'FAILED' : 'READY', 'PROCESSOR_UNAVAILABLE');
  assert.equal(await run(f), ticket.key);
  assert.deepEqual(f.calls, ['retry', 'wait']);
  f.api.get = async () => state('FAILED', 'INVALID_VIDEO');
  await assert.rejects(run(f), /choose another video/);
  assert.equal(f.calls.filter(c => c === 'retry').length, 1);
});

test('polling is bounded and cancellation preserves the draft for explicit retry', async () => {
  const f = setup({ get: async () => state('PROCESSING') });
  await assert.rejects(run(f), /still processing/);
  assert.equal(f.calls.filter(c => c === 'wait').length, 180);
  assert.equal(f.draft.uploaded, true);
  const controller = new AbortController();
  controller.abort();
  f.signal = controller.signal;
  await assert.rejects(run(f));
  assert.equal(f.calls.filter(c => c === 'put').length, 1);
});
