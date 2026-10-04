import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { changeTask, DEV_PET_ID, getTask, getTaskHistory, rateTask, type PetTask, type TaskEvent } from '@/lib/api';
import { colors } from '@/lib/theme';

export default function TaskDetailScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ id: string }>();
  const id = Number(params.id);
  const [task, setTask] = useState<PetTask | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<TaskEvent[]>([]);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyRetry, setHistoryRetry] = useState(0);
  const [score, setScore] = useState(5);
  const [comment, setComment] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try { setTask(await getTask(id)); setError(null); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load this task.'); }
    finally { setLoading(false); }
  }, [id]);
  useFocusEffect(useCallback(() => { void load(); }, [load]));

  async function act(action: 'accept' | 'start' | 'complete' | 'cancel') {
    if (busy) return;
    setBusy(true);
    setError(null);
    try { setTask(await changeTask(id, action)); setConfirmCancel(false); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not update this task.'); }
    finally { setBusy(false); }
  }

  const mine = task?.creatorPetId === DEV_PET_ID;
  const assignedToMe = task?.assigneePetId === DEV_PET_ID;
  const participant = mine || assignedToMe;

  useEffect(() => {
    setScore(task?.rating?.score ?? 5);
    setComment(task?.rating?.comment ?? '');
  }, [task?.rating?.updatedAt, task?.id]);

  useEffect(() => {
    if (!participant) { setHistory([]); setHistoryError(null); return; }
    let current = true;
    setHistoryLoading(true);
    setHistoryError(null);
    void getTaskHistory(id).then(events => { if (current) setHistory(events); })
      .catch(cause => { if (current) setHistoryError(cause instanceof Error ? cause.message : 'Could not load history.'); })
      .finally(() => { if (current) setHistoryLoading(false); });
    return () => { current = false; };
  }, [id, participant, task?.status, historyRetry]);

  async function saveRating() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try { setTask(await rateTask(id, score, comment)); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not save your rating.'); }
    finally { setBusy(false); }
  }

  const action = task?.status === 'OPEN' && !mine ? 'accept'
    : task?.status === 'ACCEPTED' && assignedToMe ? 'start'
      : task?.status === 'IN_PROGRESS' && assignedToMe ? 'complete' : null;
  const label = action === 'accept' ? 'Accept task' : action === 'start' ? 'Start task' : 'Mark completed';

  return <SafeAreaView style={styles.safe}>
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} accessibilityLabel="Go back">
          <Ionicons name="arrow-back" size={24} color={colors.ink} />
        </Pressable>
        <Text style={styles.heading}>Task details</Text><View style={{ width: 24 }} />
      </View>
      {loading && !task && <ActivityIndicator color={colors.accent} />}
      {error && <Text style={styles.error}>{error}</Text>}
      {task && <View style={styles.card}>
        <Text style={styles.status}>{task.status.replaceAll('_', ' ')}</Text>
        <Text style={styles.title}>{task.title}</Text>
        <Text style={styles.note}>{task.category.replaceAll('_', ' ')} | Posted by {task.creatorName}</Text>
        <Text style={styles.body}>{task.description}</Text>
        <Text style={styles.label}>{task.locationExact ? 'Task location' : 'Approximate location'}</Text>
        <Text style={styles.note}>{task.latitude.toFixed(task.locationExact ? 5 : 2)}, {task.longitude.toFixed(task.locationExact ? 5 : 2)}</Text>
        {task.assigneeName && <><Text style={styles.label}>Accepted by</Text>
          <Text style={styles.note}>{task.assigneeName}</Text></>}
        <Text style={styles.note}>Posted {new Date(task.createdAt).toLocaleString()}</Text>
        {task.rating && <>
          <Text style={styles.label}>Creator's rating</Text>
          <Text style={styles.note}>{task.rating.score} / 5{task.rating.comment ? ` | ${task.rating.comment}` : ''}</Text>
        </>}
      </View>}
      {task && action && <Pressable style={styles.button} onPress={() => void act(action)} disabled={busy}>
        {busy ? <ActivityIndicator color="white" /> : <Text style={styles.buttonText}>{label}</Text>}
      </Pressable>}
      {task && mine && (task.status === 'OPEN' || task.status === 'ACCEPTED') &&
        (confirmCancel ? <View style={styles.cancelRow}>
          <Pressable style={styles.cancelButton} onPress={() => void act('cancel')} disabled={busy}>
            <Text style={styles.cancelText}>Confirm cancellation</Text>
          </Pressable>
          <Pressable onPress={() => setConfirmCancel(false)}><Text style={styles.note}>Keep task</Text></Pressable>
        </View> : <Pressable style={styles.cancelButton} onPress={() => setConfirmCancel(true)}>
          <Text style={styles.cancelText}>Cancel task</Text>
        </Pressable>)}
      {error && task && <Pressable onPress={() => void load()}><Text style={styles.note}>Refresh task</Text></Pressable>}
      {task && mine && task.status === 'COMPLETED' && <View style={styles.section}>
        <Text style={styles.label}>Rate this completed task</Text>
        <View style={styles.scores}>{[1, 2, 3, 4, 5].map(value => <Pressable key={value}
          style={[styles.score, value === score && styles.selectedScore]} onPress={() => setScore(value)}
          accessibilityLabel={`Rate ${value} out of 5`}>
          <Text style={value === score ? styles.buttonText : styles.scoreText}>{value}</Text>
        </Pressable>)}</View>
        <TextInput style={styles.comment} value={comment} onChangeText={setComment} maxLength={500}
          multiline textAlignVertical="top" placeholder="Optional comment" />
        <Pressable style={styles.button} disabled={busy} onPress={() => void saveRating()}>
          {busy ? <ActivityIndicator color="white" /> : <Text style={styles.buttonText}>Save rating</Text>}
        </Pressable>
      </View>}
      {participant && <View style={styles.section}>
        <Text style={styles.label}>Task history</Text>
        {historyLoading && <ActivityIndicator color={colors.accent} />}
        {historyError && <Pressable onPress={() => setHistoryRetry(value => value + 1)}>
          <Text style={styles.error}>{historyError} Try again</Text>
        </Pressable>}
        {history.map(event => <View key={event.id} style={styles.event}>
          <Text style={styles.scoreText}>{event.status.replaceAll('_', ' ')}</Text>
          <Text style={styles.note}>{event.actorName} | {new Date(event.createdAt).toLocaleString()}</Text>
        </View>)}
      </View>}
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { width: '100%', maxWidth: 600, alignSelf: 'center', padding: 24, paddingBottom: 50 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 22 },
  heading: { color: colors.ink, fontSize: 22, fontWeight: '900' },
  card: { backgroundColor: colors.card, borderRadius: 18, padding: 22, borderWidth: 1, borderColor: colors.line },
  status: { color: colors.accent, fontWeight: '900', fontSize: 12, letterSpacing: 1 },
  title: { color: colors.ink, fontSize: 25, fontWeight: '900', marginTop: 10 },
  body: { color: colors.ink, fontSize: 16, lineHeight: 24, marginTop: 24 },
  label: { color: colors.ink, fontWeight: '800', marginTop: 22 },
  note: { color: colors.muted, marginTop: 8, lineHeight: 20 },
  button: { backgroundColor: colors.accent, borderRadius: 16, minHeight: 56,
    alignItems: 'center', justifyContent: 'center', marginTop: 24 },
  buttonText: { color: 'white', fontWeight: '800', fontSize: 16 },
  cancelButton: { padding: 16, alignItems: 'center', marginTop: 12 },
  cancelText: { color: '#B23725', fontWeight: '800' },
  cancelRow: { alignItems: 'center' },
  error: { color: '#B23725', marginVertical: 12 },
  section: { marginTop: 12 },
  scores: { flexDirection: 'row', gap: 10, marginTop: 14, marginBottom: 12 },
  score: { width: 44, height: 44, borderRadius: 12, backgroundColor: colors.greenPale,
    justifyContent: 'center', alignItems: 'center' },
  selectedScore: { backgroundColor: colors.green },
  scoreText: { color: colors.green, fontWeight: '800' },
  comment: { padding: 16, borderRadius: 14, backgroundColor: colors.card, color: colors.ink, minHeight: 80 },
  event: { paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: colors.line },
});
