import assert from 'node:assert/strict';
import test from 'node:test';
import { notificationTarget, parseRealtimeEvent, reconnectDelay } from './realtimeState.ts';
import { deliveryLabel, mergeConversation, mergeMessages } from './messageState.ts';

test('only known, well-formed realtime hints are accepted', () => {
  assert.deepEqual(parseRealtimeEvent('{"type":"SYNC"}'), { type: 'SYNC' });
  assert.deepEqual(parseRealtimeEvent('{"type":"PONG"}'), { type: 'PONG' });
  assert.deepEqual(parseRealtimeEvent('{"type":"MESSAGE","conversationId":3,"messageId":8,"senderPetId":1,"body":"ignored"}'),
    { type: 'MESSAGE', conversationId: 3, messageId: 8, senderPetId: 1 });
  for (const input of ['PONG', 'null', '[]', '{"type":"MESSAGE","conversationId":-1}', '{"type":"OTHER"}']) {
    assert.equal(parseRealtimeEvent(input), null);
  }
});

test('reconnect backoff is bounded and notification navigation requires an owned REST lookup', () => {
  assert.deepEqual([0, 1, 2, 3, 10].map(reconnectDelay), [1000, 2000, 4000, 8000, 30000]);
  assert.equal(notificationTarget({ url: 'https://example.com', targetId: 1 }), null);
  assert.equal(notificationTarget({ notificationId: '1' }), null);
  assert.equal(notificationTarget({ notificationId: 0 }), null);
  assert.deepEqual(notificationTarget({ notificationId: 2, targetId: 100, url: 'ignored' }), { notificationId: 2 });
});

test('stale message and receipt responses cannot roll delivery state backwards', () => {
  const message = { id: 5, deliveryStatus: 'READ' };
  assert.equal(mergeMessages([message], [{ id: 5, deliveryStatus: 'SENT' }])[0].deliveryStatus, 'READ');
  const previous = { id: 1, peerReadThroughId: 5, peerDeliveredThroughId: 6, myDeliveredThroughId: 8 };
  const merged = mergeConversation(previous, { id: 1, peerReadThroughId: 0, peerDeliveredThroughId: 0, myDeliveredThroughId: 0 });
  assert.equal(merged.peerReadThroughId, 5);
  assert.equal(merged.myDeliveredThroughId, 8);
  assert.equal(deliveryLabel({ id: 5, deliveryStatus: 'SENT' }, merged), 'Read');
  assert.equal(deliveryLabel({ id: 6, deliveryStatus: 'SENT' }, merged), 'Delivered');
  assert.equal(deliveryLabel({ id: 7, deliveryStatus: 'SENT' }, merged), 'Sent');
});
