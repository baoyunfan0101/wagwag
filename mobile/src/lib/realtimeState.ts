export type RealtimeEvent = {
  type: 'SYNC' | 'MESSAGE' | 'RECEIPT' | 'NOTIFICATIONS' | 'PONG';
  conversationId?: number;
  messageId?: number;
  senderPetId?: number;
};

const positiveId = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0;

export function parseRealtimeEvent(raw: string): RealtimeEvent | null {
  try {
    const value = JSON.parse(raw);
    if (!value || typeof value !== 'object') return null;
    if (value.type === 'SYNC' || value.type === 'NOTIFICATIONS' || value.type === 'PONG') return { type: value.type };
    if (value.type === 'RECEIPT' && positiveId(value.conversationId)) {
      return { type: value.type, conversationId: value.conversationId };
    }
    if (value.type === 'MESSAGE' && positiveId(value.conversationId) && positiveId(value.messageId)
      && positiveId(value.senderPetId)) {
      return { type: value.type, conversationId: value.conversationId, messageId: value.messageId,
        senderPetId: value.senderPetId };
    }
  } catch { /* A malformed hint must not affect local state. */ }
  return null;
}

export function reconnectDelay(attempt: number): number {
  return Math.min(30000, 1000 * 2 ** Math.min(5, Math.max(0, attempt)));
}

export function notificationTarget(value: unknown): { notificationId: number } | null {
  if (!value || typeof value !== 'object') return null;
  const data = value as Record<string, unknown>;
  // Resolve the target through an owned REST notification, never through a supplied URL.
  return positiveId(data.notificationId) ? { notificationId: data.notificationId } : null;
}
