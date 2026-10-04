import type { ChatMessage, Conversation, PetNotification } from './api';

const rank = { SENT: 0, DELIVERED: 1, READ: 2 };

export function mergeMessages(existing: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const byId = new Map(existing.map(item => [item.id, item]));
  for (const item of incoming) {
    const previous = byId.get(item.id);
    byId.set(item.id, previous && rank[previous.deliveryStatus] > rank[item.deliveryStatus]
      ? { ...item, deliveryStatus: previous.deliveryStatus } : item);
  }
  return [...byId.values()].sort((first, second) => first.id - second.id);
}

export function mergeConversation(previous: Conversation | null, fresh: Conversation): Conversation {
  if (!previous || previous.id !== fresh.id) return fresh;
  return { ...fresh, peerDeliveredThroughId: Math.max(previous.peerDeliveredThroughId, fresh.peerDeliveredThroughId),
    peerReadThroughId: Math.max(previous.peerReadThroughId, fresh.peerReadThroughId),
    myDeliveredThroughId: Math.max(previous.myDeliveredThroughId, fresh.myDeliveredThroughId) };
}

export function deliveryLabel(message: ChatMessage, conversation: Conversation | null): string {
  if (conversation && conversation.peerReadThroughId >= message.id) return 'Read';
  if (message.deliveryStatus === 'READ') return 'Read';
  if (conversation && conversation.peerDeliveredThroughId >= message.id) return 'Delivered';
  return message.deliveryStatus === 'DELIVERED' ? 'Delivered' : 'Sent';
}

export function mergeNotifications(existing: PetNotification[], incoming: PetNotification[]): PetNotification[] {
  const byId = new Map(existing.map(item => [item.id, item]));
  for (const item of incoming) {
    const previous = byId.get(item.id);
    // Read acknowledgement is monotonic, even if an older poll response arrives afterward.
    byId.set(item.id, { ...item, readAt: item.readAt ?? previous?.readAt ?? null });
  }
  return [...byId.values()].sort((first, second) => second.id - first.id);
}
