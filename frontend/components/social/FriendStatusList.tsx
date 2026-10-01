import { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, } from 'react-native';
import { Alert } from '@/utils/alert';
import { BellRing, Heart } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import { friendService } from '@/services/friendService';
import type { MemberAttendanceStatus } from '@/types/social';
import CheerModal from './CheerModal';

// 백엔드 ErrorResponseDto 는 {status, code, message, timestamp} — code 는 2026-09-23 에 추가됐다.
// 여기 분기는 아직 HTTP status 로만 하고, 문장은 서버 message(이미 한국어)를 그대로 쓴다.
function errorMessage(e: any, fallback: string): string {
  const status = e?.response?.status;
  if (status === 409) return `${e?.response?.data?.message ?? '오늘은 이미 보냈어요.'} 내일 다시 보낼 수 있어요.`;
  if (status === 403) return '같은 모임의 멤버에게만 보낼 수 있어요.';
  return e?.response?.data?.message ?? fallback;
}

const isConflict = (e: any) => e?.response?.status === 409;

// «3일째 운동 완료 ✅» «8일째 운동 중 🔥» «최근 운동 기록이 없어요..» — 레퍼런스 문구 그대로
export function statusLabel(s: MemberAttendanceStatus): { text: string; tone: 'done' | 'active' | 'idle' } {
  if (s.attendedToday) {
    return { text: s.streak > 1 ? `${s.streak}일째 운동 완료 ✅` : '오늘 운동 완료 ✅', tone: 'done' };
  }
  if (s.streak > 0) return { text: `${s.streak}일째 운동 중 🔥`, tone: 'active' };
  return { text: '최근 운동 기록이 없어요..', tone: 'idle' };
}

interface FriendStatusListProps {
  statuses: MemberAttendanceStatus[];
  emptyText?: string;
  // 최대 N명만 (홈 미리보기용). 생략하면 전부
  limit?: number;
}

/**
 * 친구/구성원 운동 현황 카드 목록 + 재촉·응원 동작.
 * 홈(«내 모임 친구 운동 현황»)과 모임 상세(«구성원 운동 현황»)가 같이 쓴다 — 데이터 모양이 같다(/friends ↔ /groups/{id}/members/status).
 *
 * 재촉 버튼은 오늘 완료한 사람에겐 숨긴다(서버는 안 막는다 — 핸드오프 문서). 응원은 누구에게나.
 */
export default function FriendStatusList({ statuses, emptyText = '아직 모임 친구가 없어요', limit }: FriendStatusListProps) {
  const [cheerTarget, setCheerTarget] = useState<MemberAttendanceStatus | null>(null);
  const [sending, setSending] = useState(false);
  // 이 화면에서 이미 보낸 사람 — 버튼을 바로 비활성화해 409 왕복을 줄인다 (서버가 최종 판정)
  const [nudged, setNudged] = useState<Set<number>>(new Set());
  const [cheered, setCheered] = useState<Set<number>>(new Set());

  const shown = limit ? statuses.slice(0, limit) : statuses;

  const handleNudge = async (target: MemberAttendanceStatus) => {
    try {
      await friendService.nudge(target.memberId);
      setNudged((prev) => new Set(prev).add(target.memberId));
      Alert.alert('재촉 완료', `${target.username}님에게 재촉을 보냈어요 👊`);
    } catch (e: any) {
      if (isConflict(e)) setNudged((prev) => new Set(prev).add(target.memberId));
      Alert.alert('재촉 실패', errorMessage(e, '재촉을 보내지 못했어요. 잠시 후 다시 시도해주세요.'));
    }
  };

  const handleCheer = async (message: string) => {
    if (!cheerTarget) return;
    setSending(true);
    try {
      await friendService.cheer(cheerTarget.memberId, message);
      setCheered((prev) => new Set(prev).add(cheerTarget.memberId));
      setCheerTarget(null);
      Alert.alert('응원 완료', `${cheerTarget.username}님에게 응원을 보냈어요 💛`);
    } catch (e: any) {
      if (isConflict(e)) setCheered((prev) => new Set(prev).add(cheerTarget.memberId));
      Alert.alert('응원 실패', errorMessage(e, '응원을 보내지 못했어요. 잠시 후 다시 시도해주세요.'));
    } finally {
      setSending(false);
    }
  };

  if (shown.length === 0) {
    return (
      <View style={styles.emptyCard}>
        <Text style={styles.emptyText}>{emptyText}</Text>
      </View>
    );
  }

  return (
    <View style={styles.card}>
      {shown.map((s, i) => {
        const label = statusLabel(s);
        const alreadyNudged = nudged.has(s.memberId);
        const alreadyCheered = cheered.has(s.memberId);
        return (
          <View key={s.memberId} style={[styles.row, i < shown.length - 1 && styles.rowDivider]}>
            <View style={styles.avatar}>
              <Text style={styles.avatarText}>{s.username.slice(0, 1)}</Text>
            </View>
            <View style={styles.info}>
              <Text style={styles.name} numberOfLines={1}>{s.username}</Text>
              <Text style={[styles.status, styles[`status_${label.tone}`]]}>{label.text}</Text>
            </View>
            <View style={styles.actions}>
              {!s.attendedToday && (
                <TouchableOpacity
                  style={[styles.actionBtn, alreadyNudged && styles.actionBtnDisabled]}
                  onPress={() => handleNudge(s)}
                  disabled={alreadyNudged}
                  activeOpacity={0.8}
                >
                  <BellRing size={14} color={alreadyNudged ? COLORS.textMuted : COLORS.black} strokeWidth={2.25} />
                  <Text style={[styles.actionText, alreadyNudged && styles.actionTextDisabled]}>
                    {alreadyNudged ? '재촉함' : '재촉'}
                  </Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                style={[styles.actionBtn, styles.cheerBtn, alreadyCheered && styles.actionBtnDisabled]}
                onPress={() => setCheerTarget(s)}
                disabled={alreadyCheered}
                activeOpacity={0.8}
              >
                <Heart size={14} color={alreadyCheered ? COLORS.textMuted : COLORS.primary} strokeWidth={2.25} />
                <Text style={[styles.actionText, styles.cheerText, alreadyCheered && styles.actionTextDisabled]}>
                  {alreadyCheered ? '응원함' : '응원'}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        );
      })}

      <CheerModal
        visible={!!cheerTarget}
        targetName={cheerTarget?.username ?? ''}
        sending={sending}
        onClose={() => setCheerTarget(null)}
        onSend={handleCheer}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    paddingHorizontal: SPACING.lg,
  },
  emptyCard: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    alignItems: 'center',
  },
  emptyText: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: SPACING.md, gap: SPACING.md },
  rowDivider: { borderBottomWidth: 1, borderBottomColor: COLORS.divider },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: COLORS.surfaceLight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: { fontSize: FONT_SIZE.md, fontWeight: '800', color: COLORS.text },
  info: { flex: 1 },
  name: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  status: { fontSize: FONT_SIZE.xs, marginTop: 2 },
  status_done: { color: COLORS.primary },
  status_active: { color: COLORS.warning },
  status_idle: { color: COLORS.textMuted },
  actions: { flexDirection: 'row', gap: SPACING.xs },
  actionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: SPACING.md,
    paddingVertical: 6,
    borderRadius: RADIUS.full,
    backgroundColor: COLORS.primary,
  },
  cheerBtn: { backgroundColor: COLORS.primaryDim, borderWidth: 1, borderColor: COLORS.primary },
  actionBtnDisabled: { backgroundColor: COLORS.surfaceLight, borderColor: COLORS.surfaceLight },
  actionText: { fontSize: FONT_SIZE.xs, fontWeight: '700', color: COLORS.black },
  cheerText: { color: COLORS.primary },
  actionTextDisabled: { color: COLORS.textMuted },
});
