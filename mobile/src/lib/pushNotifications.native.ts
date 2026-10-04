import AsyncStorage from '@react-native-async-storage/async-storage';
import Constants from 'expo-constants';
import { randomUUID } from 'expo-crypto';
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';
import { DEV_PET_ID, disablePushDevice, getPushDevice, registerPushDevice, type PushDeviceStatus } from './api';

export const pushSupported = true;
const deviceKey = 'wagwag.push.installation';
const optInKey = `wagwag.push.opt-in.${DEV_PET_ID}`;
let installation: Promise<string> | undefined;

Notifications.setNotificationHandler({ handleNotification: async () => ({
  shouldShowBanner: true, shouldShowList: true, shouldPlaySound: false, shouldSetBadge: false,
}) });

function deviceId(): Promise<string> {
  installation ??= (async () => {
    const existing = await AsyncStorage.getItem(deviceKey);
    if (existing) return existing;
    const id = randomUUID();
    await AsyncStorage.setItem(deviceKey, id);
    return id;
  })();
  return installation;
}

async function token(): Promise<string> {
  const projectId = process.env.EXPO_PUBLIC_EAS_PROJECT_ID
    || Constants.expoConfig?.extra?.eas?.projectId || Constants.easConfig?.projectId;
  if (!projectId) throw new Error('Configure an EAS project ID before enabling device push.');
  return (await Notifications.getExpoPushTokenAsync({ projectId })).data;
}

export async function pushState(): Promise<PushDeviceStatus> {
  if (!Device.isDevice) return { registered: false, serverEnabled: false };
  const id = await deviceId();
  const state = await getPushDevice(id);
  const previousToken = await AsyncStorage.getItem(optInKey);
  if (!previousToken) return state;
  if (!(await Notifications.getPermissionsAsync()).granted) {
    await pausePush();
    return { ...state, registered: false };
  }
  const currentToken = await token();
  // Refresh rotated tokens only after explicit opt-in. A provider-disabled token requires Enable again.
  if (currentToken !== previousToken) {
    const refreshed = await registerPushDevice(id, currentToken, Platform.OS === 'ios' ? 'IOS' : 'ANDROID');
    await AsyncStorage.setItem(optInKey, currentToken);
    return refreshed;
  }
  return state;
}

export async function enablePush(): Promise<PushDeviceStatus> {
  if (!Device.isDevice) throw new Error('Device push requires a physical phone.');
  if (!(process.env.EXPO_PUBLIC_EAS_PROJECT_ID || Constants.expoConfig?.extra?.eas?.projectId || Constants.easConfig?.projectId)) {
    throw new Error('Configure an EAS project ID before enabling device push.');
  }
  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync('messages', {
      name: 'Messages and task updates', importance: Notifications.AndroidImportance.DEFAULT,
    });
  }
  const permission = await Notifications.requestPermissionsAsync();
  if (!permission.granted) throw new Error('Allow notifications in phone settings to enable device push.');
  const currentToken = await token();
  const state = await registerPushDevice(await deviceId(), currentToken, Platform.OS === 'ios' ? 'IOS' : 'ANDROID');
  await AsyncStorage.setItem(optInKey, currentToken);
  return state;
}

export async function pausePush(): Promise<void> {
  await disablePushDevice(await deviceId());
  await AsyncStorage.removeItem(optInKey);
}

export function listenForPush(open: (data: unknown) => void, refresh: () => void): () => void {
  const seen = new Set<string>();
  function respond(response: Notifications.NotificationResponse) {
    const id = response.notification.request.identifier;
    if (seen.has(id)) return;
    seen.add(id);
    open(response.notification.request.content.data);
    Notifications.clearLastNotificationResponse();
  }
  const responseListener = Notifications.addNotificationResponseReceivedListener(respond);
  const foregroundListener = Notifications.addNotificationReceivedListener(() => refresh());
  const tokenListener = Notifications.addPushTokenListener(() => refresh());
  const last = Notifications.getLastNotificationResponse();
  if (last) respond(last);
  return () => { responseListener.remove(); foregroundListener.remove(); tokenListener.remove(); };
}
