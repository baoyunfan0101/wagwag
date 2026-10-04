import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import '@/lib/walkTracking';
import { RealtimeProvider } from '@/lib/RealtimeProvider';
import { DevicePushProvider } from '@/lib/DevicePushProvider';

export default function RootLayout() {
  return (
    <RealtimeProvider><DevicePushProvider>
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: '#F8F6F0' } }} />
    </DevicePushProvider></RealtimeProvider>
  );
}
