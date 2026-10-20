import { useEffect, useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity } from 'react-native';
import { Alert } from '@/utils/alert';
import { Flame } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import { friendService } from '@/services/friendService';
import type { MemberAttendanceStatus } from '@/types/social';

// 백엔드 ErrorResponseDto 는 {status, message, timestamp} — code 필드가 없다(2026-09-17 실측, api.ts 주석과 같음).
// 그래서 분기는 HTTP status 로만 하고, 문장은 서버 message(이미 한국어)를 그대로 쓴다.
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
  // 목록에 나도 섞여 있으면 그 줄은 «(나)» 로 표시하고 버튼을 안 그린다
  meId?: number;
}

/**
 * 친구/구성원 운동 현황 + 재촉하기.
 * 홈(«내 모임 친구 운동 현황»)과 모임 상세(«구성원 운동 현황»)가 같이 쓴다 — 데이터 모양이 같다(/friends ↔ /groups/{id}/members/status).
 *
 * 재촉하기는 **오늘 운동 안 한 사람에게만** 보인다(서버는 안 막는다 — 버튼 노출은 프론트 규칙).
 * 응원 한마디는 여기가 아니라 모임 피드의 공유 글에 단다.
 */
export default function FriendStatusList({ statuses, emptyText = '아직 모임 친구가 없어요', limit, meId }: FriendStatusListProps) {
  // 오늘 이미 재촉한 사람 — 버튼을 «재촉함» 으로 잠근다. 서버 기록에서 채우므로 다른 화면에 갔다 와도 유지된다.
  // statuses 는 부모가 화면 포커스마다 새로 받아오므로, 그때마다 같이 다시 묻는다.
  const [nudged, setNudged] = useState<Set<number>>(new Set());

  useEffect(() => {
    let alive = true;
    friendService
      .getNudgedToday()
      .then((res) => alive && setNudged(new Set(res.data)))
      .catch(() => {
        // 못 받아와도 서버가 두 번째 재촉을 409 로 막는다 — 버튼만 덜 정확할 뿐
      });
    return () => {
      alive = false;
    };
  }, [statuses]);

  const shown = limit ? statuses.slice(0, limit) : statuses;

  const handleNudge = async (target: MemberAttendanceStatus) => {
    try {
      await friendService.nudge(target.memberId);
      setNudged((prev) => new Set(prev).add(target.memberId));
      Alert.alert('재촉 완료', `${target.username}님에게 재촉을 보냈어요 🔥`);
    } catch (e: any) {
      if (isConflict(e)) setNudged((prev) => new Set(prev).add(target.memberId));
      Alert.alert('재촉 실패', errorMessage(e, '재촉을 보내지 못했어요. 잠시 후 다시 시도해주세요.'));
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
        const isMe = meId != null && s.memberId === meId;
        const alreadyNudged = nudged.has(s.memberId);
        return (
          <View key={s.memberId} style={[styles.row, i < shown.length - 1 && styles.rowDivider]}>
            <View style={styles.avatar}>
              <Text style={styles.avatarText}>{s.username.slice(0, 1)}</Text>
            </View>
            <View style={styles.info}>
              <Text style={styles.name} numberOfLines={1}>{s.username}{isMe ? ' (나)' : ''}</Text>
              <Text style={[styles.status, styles[`status_${label.tone}`]]}>{label.text}</Text>
            </View>
            {!isMe && !s.attendedToday && (
              <TouchableOpacity
                style={[styles.nudgeBtn, alreadyNudged && styles.nudgeBtnDone]}
                onPress={() => handleNudge(s)}
                disabled={alreadyNudged}
                activeOpacity={0.8}
              >
                <Text style={[styles.nudgeText, alreadyNudged && styles.nudgeTextDone]}>
                  {alreadyNudged ? '재촉함' : '재촉하기'}
                </Text>
                {!alreadyNudged && <Flame size={14} color={COLORS.black} strokeWidth={2.25} fill={COLORS.black} />}
              </TouchableOpacity>
            )}
          </View>
        );
      })}
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
  nudgeBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: SPACING.md,
    paddingVertical: 7,
    borderRadius: RADIUS.sm,
    backgroundColor: COLORS.primary,
  },
  nudgeBtnDone: { backgroundColor: COLORS.surfaceLight },
  nudgeText: { fontSize: FONT_SIZE.xs, fontWeight: '800', color: COLORS.black },
  nudgeTextDone: { color: COLORS.textMuted },
});
