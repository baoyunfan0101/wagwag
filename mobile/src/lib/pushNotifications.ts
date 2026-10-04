import type { PushDeviceStatus } from './api';

export const pushSupported = false;
export async function pushState(): Promise<PushDeviceStatus> {
  return { registered: false, serverEnabled: false };
}
export async function enablePush(): Promise<PushDeviceStatus> {
  throw new Error('Device push requires a native development build on a physical phone.');
}
export async function pausePush(): Promise<void> {}
export function listenForPush(_open: (data: unknown) => void, _refresh: () => void): () => void {
  return () => {};
}
