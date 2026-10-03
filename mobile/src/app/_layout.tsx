import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import '@/lib/walkTracking';

export default function RootLayout() {
  return (
    <>
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: '#F8F6F0' } }} />
    </>
  );
}
