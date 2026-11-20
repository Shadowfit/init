import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { Check } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';

interface CheckboxProps {
  checked: boolean;
  onToggle: () => void;
  label: string;
  /** 필수/선택 표기 — 동의 항목은 무엇이 필수인지가 보여야 한다. */
  tag?: '필수' | '선택';
  /** 있으면 «보기» 가 붙는다(문서 열기). 체크 토글과 별개로 눌린다. */
  onView?: () => void;
  error?: boolean;
  accessibilityLabel?: string;
}

/**
 * 체크박스 한 줄. 동의 화면(가입·온보딩)에서 쓴다.
 * 접근성: 상태를 `accessibilityState.checked` 로 알린다 — 색만으로는 체크 여부가 전달되지 않는다.
 */
export default function Checkbox({
  checked,
  onToggle,
  label,
  tag,
  onView,
  error,
  accessibilityLabel,
}: CheckboxProps) {
  return (
    <View style={styles.row}>
      <TouchableOpacity
        style={styles.main}
        onPress={onToggle}
        activeOpacity={0.7}
        accessibilityRole="checkbox"
        accessibilityState={{ checked }}
        accessibilityLabel={accessibilityLabel ?? label}
      >
        <View style={[styles.box, checked && styles.boxChecked, error && !checked && styles.boxError]}>
          {checked && <Check size={14} color={COLORS.primaryText} strokeWidth={3} />}
        </View>
        <Text style={styles.label}>
          {tag ? <Text style={tag === '필수' ? styles.tagRequired : styles.tagOptional}>[{tag}] </Text> : null}
          {label}
        </Text>
      </TouchableOpacity>
      {onView ? (
        <TouchableOpacity
          onPress={onView}
          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
          accessibilityRole="link"
          accessibilityLabel={`${label} 보기`}
        >
          <Text style={styles.view}>보기</Text>
        </TouchableOpacity>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: SPACING.sm,
  },
  main: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
  },
  box: {
    width: 22,
    height: 22,
    borderRadius: RADIUS.sm,
    borderWidth: 1.5,
    borderColor: COLORS.cardBorder,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: COLORS.card,
  },
  boxChecked: {
    backgroundColor: COLORS.primary,
    borderColor: COLORS.primary,
  },
  boxError: {
    borderColor: COLORS.error,
  },
  label: {
    flex: 1,
    fontSize: FONT_SIZE.sm,
    color: COLORS.text,
  },
  tagRequired: { color: COLORS.primary },
  tagOptional: { color: COLORS.textMuted },
  view: {
    fontSize: FONT_SIZE.sm,
    color: COLORS.textSecondary,
    textDecorationLine: 'underline',
    paddingLeft: SPACING.md,
  },
});
