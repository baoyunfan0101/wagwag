import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeMessages, mergeNotifications } from './messageState.ts';

test('overlapping history, polling and send responses keep all messages once in order', () => {
  const sent = { id: 4, clientMessageId: 'same-send', body: 'hello' };
  const existing = [{ id: 2 }, sent];
  const result = mergeMessages(existing, [{ id: 1 }, { id: 2 }, { id: 3 }, sent, { id: 5 }]);
  assert.deepEqual(result.map(message => message.id), [1, 2, 3, 4, 5]);
  assert.equal(existing.length, 2);
  assert.equal(result.filter(message => message.clientMessageId === 'same-send').length, 1);
});

test('stale polling cannot turn a read notification back into unread', () => {
  const existing = [{ id: 3, readAt: '2026-10-04T03:00:00Z' }, { id: 1, readAt: null }];
  const result = mergeNotifications(existing, [{ id: 4, readAt: null }, { id: 3, readAt: null },
    { id: 1, readAt: '2026-10-04T03:01:00Z' }]);
  assert.deepEqual(result.map(item => item.id), [4, 3, 1]);
  assert.equal(result[1].readAt, existing[0].readAt);
  assert.equal(result[2].readAt, '2026-10-04T03:01:00Z');
  assert.equal(existing[1].readAt, null);
});
