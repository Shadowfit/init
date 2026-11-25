import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import { moodDisplay } from '@/utils/dailyLog';

interface Props {
  /** 그 날 남긴 메모. 없으면 null/undefined. */
  memo?: string | null;
  /** 그 날의 기분(백엔드 Mood). 없거나 모르는 값이면 «없음» 으로 본다. */
  mood?: string | null;
  onEdit: () => void;
}

/**
 * 홈 날짜 상세의 «운동 일지» 카드 — 그 날 남긴 기분·메모를 보여 주고, 작성·수정 입구가 된다.
 * 기록이 없으면 «남겨보세요» 안내만 보인다(에러가 아니라 아직 안 쓴 상태).
 */
export default function DailyLogCard({ memo, mood, onEdit }: Props) {
  const moodInfo = moodDisplay(mood);
  const hasContent = !!memo || !!moodInfo;

  return (
    <View style={styles.card}>
      <View style={styles.header}>
        <Text style={styles.title}>운동 일지</Text>
        <TouchableOpacity
          testID="daily-log-edit"
          onPress={onEdit}
          hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
          accessibilityRole="button"
          accessibilityLabel={hasContent ? '운동 일지 수정' : '운동 일지 작성'}
        >
          <Text style={styles.editText}>{hasContent ? '수정' : '작성'}</Text>
        </TouchableOpacity>
      </View>

      {hasContent ? (
        <>
          {moodInfo && (
            <Text testID="daily-log-mood" style={styles.mood}>
              {moodInfo.emoji} {moodInfo.label}
            </Text>
          )}
          {!!memo && (
            <Text testID="daily-log-memo" style={styles.memo}>
              {memo}
            </Text>
          )}
        </>
      ) : (
        <Text testID="daily-log-empty" style={styles.empty}>
          운동 소감과 기분을 남겨보세요
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginHorizontal: SPACING.lg,
    marginTop: SPACING.sm,
    gap: SPACING.sm,
  },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  title: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  editText: { fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.primary },
  mood: { fontSize: FONT_SIZE.md, color: COLORS.text, fontWeight: '600' },
  memo: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, lineHeight: 20 },
  empty: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted },
});
