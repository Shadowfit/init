import { useCallback, useState } from 'react';
import { View, Text, TouchableOpacity, ActivityIndicator, StyleSheet } from 'react-native';
import { ChevronDown, ChevronUp } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import LoadError from '@/components/ui/LoadError';
import { exercisesService } from '@/services/exercisesService';
import { toRows, visibleRows, type FeedbackRow } from '@/utils/feedbackEvents';
import { devWarn } from '@/utils/devLog';

interface Props {
  sessionId: number;
  /** 집계의 총 횟수 — 접힌 버튼에 «몇 번이었나» 를 미리 보여 준다 */
  totalCount: number;
  /** 점수를 못 낸 세션이면 싱크로율 칸을 비운다 */
  hasScore: boolean;
}

type Status = 'idle' | 'loading' | 'error' | 'ready';

/**
 * 리포트의 «자세 교정 알림» 을 시간순으로 펼쳐 보는 목록 — GET /sessions/{id}/feedbacks.
 *
 * 위쪽 집계(종류별 횟수·평균)는 «무엇이 문제였나» 를 말하고, 이 목록은 «언제 났나» 를 말한다.
 * 서버가 페이징 없이 전부 주므로 **펼칠 때 한 번만** 받고, 처음엔 앞 20개만 그린다.
 */
export default function FeedbackTimeline({ sessionId, totalCount, hasScore }: Props) {
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<Status>('idle');
  const [rows, setRows] = useState<FeedbackRow[]>([]);
  const [showAll, setShowAll] = useState(false);

  const load = useCallback(() => {
    setStatus('loading');
    exercisesService
      .getSessionFeedbacks(sessionId)
      .then((res) => {
        setRows(toRows(res.data, hasScore));
        setStatus('ready');
      })
      .catch((e) => {
        devWarn('[feedbacks] status=', e?.response?.status);
        setStatus('error');
      });
  }, [sessionId, hasScore]);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    // 처음 펼칠 때만 받는다. 실패했으면 다시 펼칠 때 재시도한다.
    if (next && (status === 'idle' || status === 'error')) load();
  };

  const { shown, hidden } = visibleRows(rows, showAll);

  return (
    <View style={styles.wrap}>
      <TouchableOpacity
        style={styles.toggle}
        onPress={toggle}
        activeOpacity={0.8}
        accessibilityRole="button"
        accessibilityState={{ expanded: open }}
      >
        <Text style={styles.toggleText}>{open ? '발생 순서 접기' : `발생 순서로 보기 (${totalCount}회)`}</Text>
        {open ? (
          <ChevronUp size={16} color={COLORS.primary} strokeWidth={2} />
        ) : (
          <ChevronDown size={16} color={COLORS.primary} strokeWidth={2} />
        )}
      </TouchableOpacity>

      {open && status === 'loading' && <ActivityIndicator color={COLORS.primary} style={styles.loading} />}
      {open && status === 'error' && (
        <LoadError message="자세 교정 알림 목록을 불러오지 못했어요." onRetry={load} />
      )}
      {open && status === 'ready' && rows.length === 0 && (
        <Text style={styles.empty}>기록된 알림이 없어요.</Text>
      )}
      {open && status === 'ready' && rows.length > 0 && (
        <View style={styles.list}>
          {shown.map((r, i) => (
            <View key={r.key} style={[styles.row, i < shown.length - 1 && styles.rowDivider]}>
              <Text style={styles.time}>{r.time}</Text>
              <Text style={styles.label} numberOfLines={1}>{r.label}</Text>
              {r.sync !== undefined && <Text style={styles.sync}>{r.sync}</Text>}
            </View>
          ))}
          {hidden > 0 && (
            <TouchableOpacity style={styles.more} onPress={() => setShowAll(true)} activeOpacity={0.8}>
              <Text style={styles.moreText}>나머지 {hidden}개 더 보기</Text>
            </TouchableOpacity>
          )}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginTop: SPACING.md },
  toggle: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingVertical: SPACING.sm },
  toggleText: { color: COLORS.primary, fontSize: FONT_SIZE.sm, fontWeight: '700' },
  loading: { marginVertical: SPACING.md },
  empty: { color: COLORS.textMuted, fontSize: FONT_SIZE.sm, textAlign: 'center', paddingVertical: SPACING.md },
  list: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    paddingHorizontal: SPACING.lg,
    marginTop: SPACING.xs,
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, paddingVertical: SPACING.sm },
  rowDivider: { borderBottomWidth: 1, borderBottomColor: COLORS.divider },
  time: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, fontVariant: ['tabular-nums'], width: 62 },
  label: { flex: 1, color: COLORS.text, fontSize: FONT_SIZE.sm },
  sync: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, fontWeight: '600' },
  more: { alignItems: 'center', paddingVertical: SPACING.md },
  moreText: { color: COLORS.primary, fontSize: FONT_SIZE.sm, fontWeight: '700' },
});
