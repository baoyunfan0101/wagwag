import { Ionicons } from '@expo/vector-icons';
import { randomUUID } from 'expo-crypto';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, AppState, FlatList, KeyboardAvoidingView, Platform, Pressable,
  StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { DEV_PET_ID, getConversation, getMessages, sendMessage,
  type ChatMessage, type Conversation, type MessageInput } from '@/lib/api';
import { mergeMessages } from '@/lib/messageState';
import { useForegroundPolling } from '@/lib/useForegroundPolling';
import { colors } from '@/lib/theme';

export default function ConversationScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const conversationId = Number(id);
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [nextBeforeId, setNextBeforeId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [olderError, setOlderError] = useState<string | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
  const [body, setBody] = useState('');
  const [pending, setPending] = useState<MessageInput | null>(null);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const generation = useRef(0);
  const ready = useRef(false);
  const polling = useRef(false);
  const olderBusy = useRef(false);
  const sendBusy = useRef(false);
  const pendingRef = useRef<MessageInput | null>(null);
  const receivedThrough = useRef(0);

  useEffect(() => {
    pendingRef.current = null;
    setPending(null);
    setBody('');
    setSendError(null);
  }, [conversationId]);

  const load = useCallback(async () => {
    const current = ++generation.current;
    ready.current = false;
    setLoading(true);
    setError(null);
    setPollError(null);
    setOlderError(null);
    setConversation(null);
    setMessages([]);
    setNextBeforeId(null);
    if (!Number.isSafeInteger(conversationId) || conversationId < 1) {
      setError('This conversation link is invalid.');
      setLoading(false);
      return;
    }
    try {
      const [detail, result] = await Promise.all([getConversation(conversationId), getMessages(conversationId)]);
      if (current !== generation.current) return;
      setConversation(detail);
      setMessages(result.items);
      setNextBeforeId(result.nextBeforeId);
      receivedThrough.current = result.items.at(-1)?.id ?? 0;
      ready.current = true;
    } catch {
      if (current === generation.current) setError('Could not load this conversation. Try again.');
    } finally {
      if (current === generation.current) setLoading(false);
    }
  }, [conversationId]);

  useFocusEffect(useCallback(() => {
    void load();
    return () => { ready.current = false; generation.current++; };
  }, [load]));

  async function poll() {
    if (!ready.current || polling.current) return;
    polling.current = true;
    const current = generation.current;
    try {
      const detail = await getConversation(conversationId);
      if (current !== generation.current) return;
      setConversation(detail);
      let more = true;
      while (more && ready.current && (!AppState.currentState || AppState.currentState === 'active')) {
        const result = await getMessages(conversationId, { afterId: receivedThrough.current, limit: 50 });
        if (current !== generation.current) return;
        setMessages(existing => mergeMessages(existing, result.items));
        receivedThrough.current = result.items.at(-1)?.id ?? receivedThrough.current;
        more = result.nextAfterId !== null;
      }
      setPollError(null);
    } catch {
      if (current === generation.current) setPollError('Could not refresh messages. Tap to retry.');
    } finally { polling.current = false; }
  }

  useForegroundPolling(poll);

  async function loadOlder() {
    if (nextBeforeId === null || olderBusy.current || !ready.current) return;
    olderBusy.current = true;
    const current = generation.current;
    setLoadingOlder(true);
    setOlderError(null);
    try {
      const result = await getMessages(conversationId, { beforeId: nextBeforeId });
      if (current !== generation.current) return;
      setMessages(existing => mergeMessages(existing, result.items));
      setNextBeforeId(result.nextBeforeId);
    } catch {
      if (current === generation.current) setOlderError('Could not load earlier messages. Try again.');
    } finally { olderBusy.current = false; setLoadingOlder(false); }
  }

  async function send() {
    if (sendBusy.current || !ready.current || (!pendingRef.current && (!body.trim() || !conversation?.canMessage))) return;
    const input = pendingRef.current ?? { clientMessageId: randomUUID(), body: body.trim() };
    pendingRef.current = input;
    setPending(input);
    sendBusy.current = true;
    const current = generation.current;
    setSending(true);
    setSendError(null);
    try {
      const saved = await sendMessage(conversationId, input);
      if (current !== generation.current) return;
      setMessages(existing => mergeMessages(existing, [saved]));
      // Only polling advances receivedThrough: intervening incoming messages must still be fetched.
      pendingRef.current = null;
      setPending(null);
      setBody('');
    } catch {
      if (current === generation.current) setSendError('Could not send. Your message is kept here; retry or clear it.');
    } finally { sendBusy.current = false; setSending(false); }
  }

  function clearMessage() {
    if (sendBusy.current) return;
    pendingRef.current = null;
    setPending(null);
    setBody('');
    setSendError(null);
  }

  return <SafeAreaView style={styles.safe}>
    <KeyboardAvoidingView style={styles.container} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={24} color={colors.ink} />
        </Pressable>
        <Text style={styles.title} numberOfLines={1}>{conversation?.petName || 'Conversation'}</Text>
        <View style={{ width: 24 }} />
      </View>
      {loading ? <View style={styles.center}><ActivityIndicator color={colors.accent} /></View> :
        error ? <View style={styles.center}><Pressable onPress={() => void load()}><Text style={styles.error}>{error}</Text></Pressable></View> : <>
          {conversation && !conversation.canMessage && <Text style={styles.notice}>Messaging is unavailable. Your previous history is still here.</Text>}
          {pollError && <Pressable onPress={() => void poll()}><Text style={styles.error}>{pollError}</Text></Pressable>}
          <FlatList style={styles.list} inverted data={[...messages].reverse()} keyExtractor={message => String(message.id)}
            contentContainerStyle={styles.messageContent} maintainVisibleContentPosition={{ minIndexForVisible: 0 }}
            keyboardShouldPersistTaps="handled"
            renderItem={({ item }) => {
              const mine = item.senderPetId === DEV_PET_ID;
              return <View style={[styles.bubble, mine ? styles.mine : styles.theirs]}>
                <Text style={[styles.message, mine && styles.myMessage]}>{item.body}</Text>
                <Text style={[styles.date, mine && styles.myDate]}>{new Date(item.createdAt).toLocaleString()}</Text>
              </View>;
            }}
            ListEmptyComponent={<Text style={styles.notice}>Say hello to start the conversation.</Text>}
            ListFooterComponent={loadingOlder ? <ActivityIndicator color={colors.accent} /> : nextBeforeId !== null ?
              <Pressable style={styles.older} onPress={() => void loadOlder()}><Text style={styles.olderText}>
                {olderError || 'Load earlier messages'}
              </Text></Pressable> : messages.length > 0 ? <Text style={styles.notice}>Beginning of conversation</Text> : null}
          />
          {sendError && <Text style={styles.error}>{sendError}</Text>}
          {pending && !sending && <Pressable onPress={clearMessage}><Text style={styles.clear}>Clear message</Text></Pressable>}
          <View style={styles.composer}>
            <TextInput style={styles.input} value={body} onChangeText={setBody} maxLength={2000} multiline
              placeholder="Message" placeholderTextColor={colors.muted} accessibilityLabel="Message text"
              editable={!pending && !sending && !!conversation?.canMessage} />
            <Pressable style={[styles.send, (sending || (!pending && (!body.trim() || !conversation?.canMessage))) && styles.disabled]}
              onPress={() => void send()} disabled={sending || (!pending && (!body.trim() || !conversation?.canMessage))}>
              {sending ? <ActivityIndicator color="white" /> : <Text style={styles.sendText}>{pending ? 'Retry' : 'Send'}</Text>}
            </Pressable>
          </View>
        </>}
    </KeyboardAvoidingView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  container: { flex: 1, width: '100%', maxWidth: 600, alignSelf: 'center' },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 22, gap: 14 },
  title: { flex: 1, textAlign: 'center', color: colors.ink, fontSize: 21, fontWeight: '900' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  list: { flex: 1 },
  messageContent: { paddingHorizontal: 20, paddingVertical: 12 },
  bubble: { maxWidth: '85%', borderRadius: 17, padding: 14, marginVertical: 5 },
  mine: { backgroundColor: colors.green, alignSelf: 'flex-end' },
  theirs: { backgroundColor: colors.card, alignSelf: 'flex-start', borderWidth: 1, borderColor: colors.line },
  message: { color: colors.ink, fontSize: 16, lineHeight: 23 },
  myMessage: { color: 'white' },
  date: { color: colors.muted, fontSize: 10, marginTop: 7 },
  myDate: { color: '#DCE9DF' },
  notice: { color: colors.muted, textAlign: 'center', marginHorizontal: 20, marginVertical: 12, lineHeight: 20 },
  error: { color: '#B23725', textAlign: 'center', marginHorizontal: 20, marginVertical: 10 },
  older: { alignItems: 'center', padding: 15 },
  olderText: { color: colors.green, fontWeight: '800' },
  composer: { flexDirection: 'row', alignItems: 'flex-end', gap: 10, padding: 16, borderTopWidth: 1, borderColor: colors.line },
  input: { flex: 1, minHeight: 48, maxHeight: 130, padding: 13, backgroundColor: colors.card,
    color: colors.ink, borderRadius: 14, fontSize: 16 },
  send: { minHeight: 48, minWidth: 65, borderRadius: 14, backgroundColor: colors.accent,
    alignItems: 'center', justifyContent: 'center', paddingHorizontal: 12 },
  sendText: { color: 'white', fontWeight: '800' },
  disabled: { opacity: 0.45 },
  clear: { color: colors.muted, textAlign: 'center', paddingVertical: 8 },
});
