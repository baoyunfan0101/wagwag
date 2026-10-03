import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import { Platform } from 'react-native';
import type { WalkInput, WalkPoint } from './api';
import { appendGpsSamples } from './walkRoute';

const TASK = 'wagwag-walk-location';
const DRAFT_KEY = 'wagwag:active-walk';
export type WalkDraft = {
  clientWalkId: string;
  startedAt: string;
  endedAt: string | null;
  points: WalkPoint[];
  background: boolean;
  error: string | null;
};

let queue: Promise<unknown> = Promise.resolve();
let foregroundSubscription: Location.LocationSubscription | null = null;
const listeners = new Set<(draft: WalkDraft | null) => void>();

function serial<T>(operation: () => Promise<T>): Promise<T> {
  const next = queue.then(operation, operation);
  queue = next.catch(() => undefined);
  return next;
}

async function readDraft(): Promise<WalkDraft | null> {
  const value = await AsyncStorage.getItem(DRAFT_KEY);
  return value ? JSON.parse(value) as WalkDraft : null;
}

async function writeDraft(draft: WalkDraft | null) {
  if (draft) await AsyncStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
  else await AsyncStorage.removeItem(DRAFT_KEY);
  for (const listener of listeners) listener(draft);
}

async function appendLocations(locations: Location.LocationObject[], clientWalkId?: string) {
  return serial(async () => {
    const draft = await readDraft();
    if (!draft || draft.endedAt || (clientWalkId && draft.clientWalkId !== clientWalkId)) return;
    draft.points = appendGpsSamples(draft.points, draft.startedAt, locations);
    if (draft.points.length >= 2000) draft.error = 'Route limit reached. Stop and save this walk.';
    await writeDraft(draft);
  });
}

async function reportError(message: string, clientWalkId?: string) {
  return serial(async () => {
    const draft = await readDraft();
    if (draft && !draft.endedAt && (!clientWalkId || draft.clientWalkId === clientWalkId)) {
      await writeDraft({ ...draft, error: message });
    }
  });
}

// Loaded by the root layout so native background launches can find the task.
if (Platform.OS !== 'web' && !TaskManager.isTaskDefined(TASK)) {
  TaskManager.defineTask<{ locations: Location.LocationObject[] }>(TASK, async ({ data, error }) => {
    if (error) { await reportError('Location tracking was interrupted. Return to your walk.'); return; }
    if (data) await appendLocations(data.locations);
  });
}

export function subscribeWalk(listener: (draft: WalkDraft | null) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function loadWalkDraft(): Promise<WalkDraft | null> {
  return serial(async () => {
    const draft = await readDraft();
    if (draft && !draft.endedAt) {
      const running = draft.background && Platform.OS !== 'web'
        ? await Location.hasStartedLocationUpdatesAsync(TASK) : foregroundSubscription !== null;
      if (!running) {
        draft.endedAt = draft.points.at(-1)?.recordedAt ?? draft.startedAt;
        draft.error = 'Recording was interrupted. Save the recorded route or discard it.';
        await writeDraft(draft);
      }
    }
    return draft;
  });
}

export async function startWalkTracking(clientWalkId: string, startedAt: string,
  first: Location.LocationObject, background: boolean): Promise<WalkDraft> {
  return serial(async () => {
    if (await readDraft()) throw new Error('Finish or discard the existing walk first.');
    const draft: WalkDraft = { clientWalkId, startedAt, endedAt: null,
      points: appendGpsSamples([], startedAt, [first]), background, error: null };
    await writeDraft(draft);
    try {
      if (background) {
        await Location.startLocationUpdatesAsync(TASK, {
          accuracy: Location.Accuracy.High, distanceInterval: 5, timeInterval: 3000,
          activityType: Location.ActivityType.Fitness, pausesUpdatesAutomatically: false,
          showsBackgroundLocationIndicator: true,
          foregroundService: { notificationTitle: 'WagWag walk recording',
            notificationBody: 'Recording your route. Open WagWag to stop and save.',
            notificationColor: '#335A4D' },
        });
      } else {
        foregroundSubscription = await Location.watchPositionAsync(
          { accuracy: Location.Accuracy.High, distanceInterval: 5, timeInterval: 3000 },
          (location) => { void appendLocations([location], clientWalkId).catch(() => undefined); },
          () => { void reportError('Location tracking was interrupted.', clientWalkId).catch(() => undefined); },
        );
      }
      return draft;
    } catch (error) {
      await stopLocationUpdates();
      await writeDraft(null);
      throw error;
    }
  });
}

async function stopLocationUpdates() {
  foregroundSubscription?.remove();
  foregroundSubscription = null;
  if (Platform.OS !== 'web' && await Location.hasStartedLocationUpdatesAsync(TASK)) {
    await Location.stopLocationUpdatesAsync(TASK);
  }
}

export function stopWalkTracking(): Promise<WalkInput> {
  return serial(async () => {
    const draft = await readDraft();
    if (!draft) throw new Error('No active walk.');
    await stopLocationUpdates();
    if (!draft.endedAt) {
      draft.endedAt = new Date(Math.max(Date.now(), Date.parse(draft.points.at(-1)?.recordedAt ?? draft.startedAt))).toISOString();
      await writeDraft(draft);
    }
    if (draft.points.length === 0) throw new Error('No accurate GPS points were recorded. Discard or keep this route.');
    return { clientWalkId: draft.clientWalkId, startedAt: draft.startedAt,
      endedAt: draft.endedAt, points: draft.points };
  });
}

export function discardWalkDraft(): Promise<void> {
  return serial(async () => {
    await stopLocationUpdates();
    await writeDraft(null);
  });
}

export function clearSavedWalk(clientWalkId: string): Promise<void> {
  return serial(async () => {
    const draft = await readDraft();
    if (draft?.clientWalkId === clientWalkId && draft.endedAt) await writeDraft(null);
  });
}
