import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import { REACTION_EMOJI, REACTION_KINDS, countOf, hasReacted, normalizeSummary } from '@/utils/reactions';
import type { ReactionKind, ReactionSummary } from '@/types/social';

interface Props {
  summary: ReactionSummary | null | undefined;
  /** 누르면 호출 — 낙관적 갱신·요청·되돌리기는 부모가 한다(피드 상태가 거기 있다) */
  onToggle: (kind: ReactionKind) => void;
  /** 이 글에서 요청이 가는 중인 종류 — 연타로 같은 요청이 겹치지 않게 잠근다 */
  pending?: ReadonlySet<ReactionKind>;
}

/** 모임 피드 글 아래 💗🔥 — 한 글에 두 종류 모두 달 수 있고, 다시 누르면 취소된다. */
export default function ReactionBar({ summary, onToggle, pending }: Props) {
  const s = normalizeSummary(summary);
  return (
    <View style={styles.row}>
      {REACTION_KINDS.map((kind) => {
        const active = hasReacted(s, kind);
        const count = countOf(s, kind);
        return (
          <TouchableOpacity
            key={kind}
            style={[styles.chip, active && styles.chipActive]}
            onPress={() => onToggle(kind)}
            disabled={pending?.has(kind)}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityState={{ selected: active }}
            accessibilityLabel={`${kind === 'HEART' ? '하트' : '불꽃'} ${count}개${active ? ', 내가 누름' : ''}`}
          >
            <Text style={styles.emoji}>{REACTION_EMOJI[kind]}</Text>
            {count > 0 && <Text style={[styles.count, active && styles.countActive]}>{count}</Text>}
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: SPACING.xs },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    borderRadius: RADIUS.full,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    paddingHorizontal: SPACING.sm,
    paddingVertical: 4,
  },
  chipActive: { backgroundColor: COLORS.primary, borderColor: COLORS.primary },
  emoji: { fontSize: FONT_SIZE.sm },
  count: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, fontWeight: '600' },
  countActive: { color: COLORS.black },
});
