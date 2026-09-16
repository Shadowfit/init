import { useEffect, useState } from 'react';
import {
  Modal,
  View,
  Text,
  TouchableOpacity,
  TextInput,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { X, ChevronRight, ChevronDown } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';

// 레퍼런스 «응원 보내기» 모달의 정형 문구 — 백엔드는 문구를 모르고 message 문자열만 받는다(≤100자)
const PRESETS = [
  '😄 오늘도 힘내',
  '😊 괜찮아..',
  '👏 칭찬해요!',
  '🔥 응원해요',
  '😅 고생했어',
  '💪 할 수 있다!',
  '😆 다음에 같이 운동하자',
  '😮 대단해!',
  '🔥',
  '💯 100점 드립니다',
  '💗',
  '😎 진정한 운동인',
];

export const CHEER_MAX_LENGTH = 100;

interface CheerModalProps {
  visible: boolean;
  targetName: string;
  sending?: boolean;
  onClose: () => void;
  onSend: (message: string) => void;
}

export default function CheerModal({ visible, targetName, sending, onClose, onSend }: CheerModalProps) {
  const [selected, setSelected] = useState<string | null>(null);
  const [customOpen, setCustomOpen] = useState(false);
  const [custom, setCustom] = useState('');

  // 열 때마다 초기화 — 지난번 고른 칩이 남아 있으면 «보냈는데 다른 문구가 갔다» 가 된다
  useEffect(() => {
    if (visible) {
      setSelected(null);
      setCustomOpen(false);
      setCustom('');
    }
  }, [visible]);

  const message = customOpen && custom.trim() ? custom.trim() : selected;
  const canSend = !!message && !sending;

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.backdrop}
      >
        <View style={styles.sheet}>
          <View style={styles.header}>
            <Text style={styles.title}>응원 보내기</Text>
            <TouchableOpacity onPress={onClose} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
              <X size={22} color={COLORS.textSecondary} strokeWidth={2} />
            </TouchableOpacity>
          </View>
          <Text style={styles.subtitle}>{targetName}님에게 한마디</Text>

          <View style={styles.chips}>
            {PRESETS.map((p) => {
              const active = selected === p && !(customOpen && custom.trim());
              return (
                <TouchableOpacity
                  key={p}
                  style={[styles.chip, active && styles.chipActive]}
                  onPress={() => setSelected(p)}
                  activeOpacity={0.8}
                >
                  <Text style={[styles.chipText, active && styles.chipTextActive]}>{p}</Text>
                </TouchableOpacity>
              );
            })}
          </View>

          <TouchableOpacity style={styles.customToggle} onPress={() => setCustomOpen((v) => !v)}>
            <Text style={styles.customToggleText}>직접 입력</Text>
            {customOpen ? (
              <ChevronDown size={16} color={COLORS.primary} strokeWidth={2} />
            ) : (
              <ChevronRight size={16} color={COLORS.primary} strokeWidth={2} />
            )}
          </TouchableOpacity>
          {customOpen && (
            <View>
              <TextInput
                style={styles.input}
                placeholder="내용을 입력해주세요"
                placeholderTextColor={COLORS.textPlaceholder}
                selectionColor={COLORS.primary}
                value={custom}
                onChangeText={(t) => setCustom(t.slice(0, CHEER_MAX_LENGTH))}
                maxLength={CHEER_MAX_LENGTH}
                multiline
              />
              <Text style={styles.counter}>{custom.length}/{CHEER_MAX_LENGTH}</Text>
            </View>
          )}

          <View style={styles.actions}>
            <Button title="닫기" variant="ghost" size="md" onPress={onClose} style={styles.actionBtn} />
            <Button
              title="응원 보내기"
              size="md"
              loading={sending}
              disabled={!canSend}
              onPress={() => message && onSend(message)}
              style={styles.sendBtn}
            />
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: COLORS.overlay,
    justifyContent: 'center',
    paddingHorizontal: SPACING.xl,
  },
  sheet: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.xl,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.xl,
  },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  subtitle: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2, marginBottom: SPACING.md },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  chip: {
    paddingHorizontal: SPACING.md,
    paddingVertical: 7,
    borderRadius: RADIUS.full,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    backgroundColor: COLORS.card,
  },
  chipActive: { backgroundColor: COLORS.primary, borderColor: COLORS.primary },
  chipText: { fontSize: FONT_SIZE.sm, color: COLORS.text },
  chipTextActive: { color: COLORS.black, fontWeight: '700' },
  customToggle: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.xs,
    marginTop: SPACING.lg,
    marginBottom: SPACING.sm,
  },
  customToggleText: { fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.primary },
  input: {
    minHeight: 48,
    maxHeight: 96,
    backgroundColor: COLORS.card,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    borderRadius: RADIUS.md,
    paddingHorizontal: SPACING.md,
    paddingVertical: SPACING.sm,
    color: COLORS.text,
    fontSize: FONT_SIZE.sm,
    textAlignVertical: 'top',
  },
  counter: { alignSelf: 'flex-end', fontSize: FONT_SIZE.xs, color: COLORS.textMuted, marginTop: 2 },
  actions: { flexDirection: 'row', gap: SPACING.sm, marginTop: SPACING.lg },
  actionBtn: { flex: 1 },
  sendBtn: { flex: 2 },
});
