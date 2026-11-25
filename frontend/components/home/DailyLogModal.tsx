import { useEffect, useState } from 'react';
import {
  Modal, View, Text, TextInput, TouchableOpacity, KeyboardAvoidingView, Platform, StyleSheet,
} from 'react-native';
import Button from '@/components/ui/Button';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import { MEMO_MAX, MOODS, isDirty, toDraft, type DailyLogDraft, type Mood } from '@/utils/dailyLog';

interface Props {
  visible: boolean;
  /** «11월 25일 수요일» 처럼 사용자에게 보일 날짜. */
  dateLabel: string;
  /** 서버에 저장돼 있는 값 — 입력은 이 값으로 시작한다(저장은 둘 다 덮어쓰므로 안 보이는 값을 지우지 않게). */
  initialMemo?: string | null;
  initialMood?: string | null;
  onClose: () => void;
  /** 저장한다. 실패하면 던진다 — 모달은 열어 둔 채 오류를 보여 주고 다시 시도하게 한다. */
  onSave: (draft: DailyLogDraft) => Promise<void>;
}

/** 운동 일지(기분·메모) 입력. 저장이 성공해야 닫힌다 — 실패했는데 닫히면 쓴 글이 사라진다. */
export default function DailyLogModal({ visible, dateLabel, initialMemo, initialMood, onClose, onSave }: Props) {
  const base = toDraft(initialMemo, initialMood);
  const [memo, setMemo] = useState(base.memo ?? '');
  const [mood, setMood] = useState<Mood | null>(base.mood);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 열릴 때마다 서버 값으로 초기화한다(이전에 쓰다 닫은 초안이 다른 날짜에 남지 않게).
  useEffect(() => {
    if (!visible) return;
    const b = toDraft(initialMemo, initialMood);
    setMemo(b.memo ?? '');
    setMood(b.mood);
    setError(null);
    setSaving(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, initialMemo, initialMood]);

  const dirty = isDirty(base, { memo, mood });

  const handleSave = async () => {
    if (!dirty || saving) return;
    setSaving(true);
    setError(null);
    try {
      await onSave({ memo, mood });
      onClose();
    } catch {
      setError('저장하지 못했어요. 네트워크를 확인하고 다시 시도해주세요.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={saving ? undefined : onClose}>
      <KeyboardAvoidingView
        style={styles.overlay}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View style={styles.sheet}>
          <Text style={styles.title}>{dateLabel} 운동 일지</Text>

          <Text style={styles.label}>오늘 기분은 어땠나요?</Text>
          <View style={styles.moodRow}>
            {MOODS.map((m) => {
              const selected = mood === m.value;
              return (
                <TouchableOpacity
                  key={m.value}
                  testID={`daily-log-mood-${m.value}`}
                  // 선택한 것을 다시 누르면 해제 — 기분은 비워 둘 수 있다.
                  onPress={() => setMood(selected ? null : m.value)}
                  disabled={saving}
                  style={[styles.moodBtn, selected && styles.moodBtnSelected]}
                  accessibilityRole="button"
                  accessibilityLabel={`${m.label}`}
                  accessibilityState={{ selected }}
                >
                  <Text style={styles.moodEmoji}>{m.emoji}</Text>
                  <Text style={[styles.moodLabel, selected && styles.moodLabelSelected]} numberOfLines={1}>
                    {m.label}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>

          <Text style={styles.label}>한 줄 소감</Text>
          <TextInput
            testID="daily-log-memo-input"
            style={styles.input}
            value={memo}
            onChangeText={setMemo}
            editable={!saving}
            multiline
            maxLength={MEMO_MAX}
            placeholder="무릎 느낌, 오늘의 컨디션 등을 적어보세요"
            placeholderTextColor={COLORS.textPlaceholder}
            textAlignVertical="top"
          />
          <Text style={styles.counter}>{memo.length}/{MEMO_MAX}</Text>

          {!!error && (
            <Text testID="daily-log-error" style={styles.error} accessibilityRole="alert">
              {error}
            </Text>
          )}

          <View style={styles.actions}>
            <Button title="취소" variant="outline" size="md" onPress={onClose} disabled={saving} style={styles.actionBtn} />
            <Button
              title="저장"
              size="md"
              onPress={handleSave}
              disabled={!dirty}
              loading={saving}
              style={styles.actionBtn}
            />
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: { flex: 1, backgroundColor: COLORS.overlay, justifyContent: 'center', padding: SPACING.lg },
  sheet: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    padding: SPACING.lg,
    gap: SPACING.sm,
  },
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text, marginBottom: SPACING.xs },
  label: { fontSize: FONT_SIZE.sm, fontWeight: '600', color: COLORS.textSecondary, marginTop: SPACING.xs },
  moodRow: { flexDirection: 'row', gap: SPACING.xs },
  moodBtn: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: SPACING.sm,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    backgroundColor: COLORS.card,
  },
  moodBtnSelected: { borderColor: COLORS.primary, backgroundColor: COLORS.surfaceLight },
  moodEmoji: { fontSize: 22 },
  moodLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted, marginTop: 2 },
  moodLabelSelected: { color: COLORS.primary, fontWeight: '700' },
  input: {
    minHeight: 96,
    maxHeight: 180,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    backgroundColor: COLORS.card,
    color: COLORS.text,
    padding: SPACING.md,
    fontSize: FONT_SIZE.sm,
  },
  counter: { alignSelf: 'flex-end', fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  error: { fontSize: FONT_SIZE.sm, color: COLORS.error },
  actions: { flexDirection: 'row', gap: SPACING.sm, marginTop: SPACING.sm },
  actionBtn: { flex: 1 },
});
