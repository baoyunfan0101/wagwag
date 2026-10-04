import type { ChatMessage, PetNotification } from './api';

export function mergeMessages(existing: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const byId = new Map(existing.map(item => [item.id, item]));
  for (const item of incoming) byId.set(item.id, item);
  return [...byId.values()].sort((first, second) => first.id - second.id);
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
