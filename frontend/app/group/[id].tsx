import { useCallback, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Alert,
  ActivityIndicator,
  Share,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter, useFocusEffect } from 'expo-router';
import { Calendar, type DateData } from 'react-native-calendars';
import { ChevronLeft, Share2, RefreshCw, Crown, Heart, Flame, LogOut, Dumbbell, UserPlus } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import FriendStatusList from '@/components/social/FriendStatusList';
import { groupService } from '@/services/groupService';
import { useAuthStore } from '@/stores/authStore';
import {
  parseFeedPayload,
  type GroupAttendanceCalendar,
  type GroupDetail,
  type GroupFeedItem,
  type MemberAttendanceStatus,
  type ReactionKind,
} from '@/types/social';

const FEED_PAGE = 20;

// 출석 비율(0~1) → 칸 배경. 레퍼런스의 «농도» 를 라임 알파로 옮겼다. 0 이면 표시 없음
function attendanceStyle(ratio: number) {
  if (ratio <= 0) return undefined;
  const alpha = 0.25 + 0.75 * Math.min(ratio, 1);
  return {
    customStyles: {
      container: { backgroundColor: `rgba(202, 255, 0, ${alpha.toFixed(2)})`, borderRadius: 6 },
      text: { color: ratio >= 0.5 ? COLORS.black : COLORS.text, fontWeight: '700' as const },
    },
  };
}

function formatFeedTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`;
}

// 에러 응답은 {status, message, timestamp} — code 필드가 없어 서버 message(한국어, 예: «그룹장은 다른 멤버에게 양도한 뒤…»)를 그대로 보여준다
function errorText(e: any, fallback: string): string {
  return e?.response?.data?.message ?? fallback;
}

export default function GroupDetailScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const groupId = Number(id);
  const me = useAuthStore((s) => s.user);

  const [detail, setDetail] = useState<GroupDetail | null>(null);
  const [statuses, setStatuses] = useState<MemberAttendanceStatus[]>([]);
  const [attendance, setAttendance] = useState<GroupAttendanceCalendar | null>(null);
  const [feed, setFeed] = useState<GroupFeedItem[]>([]);
  const [nextBeforeSeq, setNextBeforeSeq] = useState<number | null>(null);
  const [feedLoading, setFeedLoading] = useState(false);
  const [loading, setLoading] = useState(true);

  const now = new Date();
  const [viewYear, setViewYear] = useState(now.getFullYear());
  const [viewMonth, setViewMonth] = useState(now.getMonth() + 1);

  const loadAttendance = useCallback((year: number, month: number) => {
    groupService
      .getAttendance(groupId, year, month)
      .then((res) => setAttendance(res.data))
      .catch((e) => console.warn('[attendance] status=', e?.response?.status));
  }, [groupId]);

  const loadFeed = useCallback(async (beforeSeq: number | null, replace: boolean) => {
    setFeedLoading(true);
    try {
      const res = await groupService.getFeed(groupId, beforeSeq, FEED_PAGE);
      setFeed((prev) => (replace ? res.data.items : [...prev, ...res.data.items]));
      setNextBeforeSeq(res.data.nextBeforeSeq);
    } catch (e: any) {
      console.warn('[feed] status=', e?.response?.status, 'data=', JSON.stringify(e?.response?.data));
    } finally {
      setFeedLoading(false);
    }
  }, [groupId]);

  const loadAll = useCallback(() => {
    if (!groupId) return;
    Promise.all([groupService.getDetail(groupId), groupService.getMemberStatuses(groupId)])
      .then(([d, s]) => {
        setDetail(d.data);
        setStatuses(s.data);
      })
      .catch((e) => {
        console.warn('[group] status=', e?.response?.status, 'data=', JSON.stringify(e?.response?.data));
        if (e?.response?.status === 403 || e?.response?.status === 404) {
          Alert.alert('모임', '이 모임에 접근할 수 없어요.', [{ text: '확인', onPress: () => router.back() }]);
        }
      })
      .finally(() => setLoading(false));
    loadAttendance(viewYear, viewMonth);
    loadFeed(null, true);
  }, [groupId, loadAttendance, loadFeed, viewYear, viewMonth, router]);

  useFocusEffect(useCallback(() => { loadAll(); }, [loadAll]));

  const activeMembers = detail?.members.filter((m) => m.status === 'ACTIVE') ?? [];
  const myRole = activeMembers.find((m) => m.memberId === me?.memberId)?.role;
  const isOwner = myRole === 'OWNER';

  const shareCode = () => {
    if (!detail) return;
    Share.share({ message: `ShadowFit 모임 «${detail.name}» 초대 코드: ${detail.inviteCode}` });
  };

  const regenerateCode = () => {
    Alert.alert('초대 코드 재발급', '이전 코드는 바로 쓸 수 없게 돼요. 재발급할까요?', [
      { text: '취소', style: 'cancel' },
      {
        text: '재발급',
        onPress: async () => {
          try {
            const res = await groupService.regenerateInviteCode(groupId);
            setDetail((d) => (d ? { ...d, inviteCode: res.data.inviteCode } : d));
          } catch (e: any) {
            Alert.alert('재발급 실패', errorText(e, '초대 코드를 재발급하지 못했어요.'));
          }
        },
      },
    ]);
  };

  const leave = () => {
    Alert.alert('모임 탈퇴', '정말 탈퇴할까요? 남긴 글과 리액션은 남아요.', [
      { text: '취소', style: 'cancel' },
      {
        text: '탈퇴',
        style: 'destructive',
        onPress: async () => {
          try {
            await groupService.leave(groupId);
            router.back();
          } catch (e: any) {
            Alert.alert('탈퇴 실패', errorText(e, '탈퇴하지 못했어요.'));
          }
        },
      },
    ]);
  };

  // 리액션 토글 — 응답의 요약으로 그 글만 갈아끼운다 (소켓 발행 없음, 재조회로 반영 — 핸드오프 문서)
  const toggleReaction = async (item: GroupFeedItem, kind: ReactionKind) => {
    const mine = item.reactionSummary.myReactions.includes(kind);
    try {
      const res = mine
        ? await groupService.removeReaction(groupId, item.seq, kind)
        : await groupService.addReaction(groupId, item.seq, kind);
      setFeed((prev) => prev.map((f) => (f.seq === item.seq ? { ...f, reactionSummary: res.data } : f)));
    } catch (e: any) {
      console.warn('[reaction] status=', e?.response?.status);
    }
  };

  // 캘린더 마킹 — attendedCount / activeMemberCount
  const markedDates: Record<string, any> = {};
  if (attendance && attendance.activeMemberCount > 0) {
    for (const d of attendance.days) {
      const st = attendanceStyle(d.attendedCount / attendance.activeMemberCount);
      if (st) markedDates[d.date] = st;
    }
  }

  if (loading || !detail) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.header}>
          <TouchableOpacity onPress={() => router.back()} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
            <ChevronLeft size={24} color={COLORS.text} strokeWidth={2} />
          </TouchableOpacity>
        </View>
        <ActivityIndicator color={COLORS.primary} style={{ marginTop: SPACING.xxxl }} />
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => router.back()} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
          <ChevronLeft size={24} color={COLORS.text} strokeWidth={2} />
        </TouchableOpacity>
        <Text style={styles.headerTitle} numberOfLines={1}>{detail.name}</Text>
        <TouchableOpacity onPress={leave} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
          <LogOut size={20} color={COLORS.textMuted} strokeWidth={2} />
        </TouchableOpacity>
      </View>

      <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={styles.scroll}>
        {/* 모임 카드 */}
        <View style={styles.infoCard}>
          <Text style={styles.groupName}>{detail.name}</Text>
          {!!detail.description && <Text style={styles.groupDesc}>{detail.description}</Text>}
          <View style={styles.badges}>
            <View style={styles.badge}><Text style={styles.badgeText}>멤버 {activeMembers.length}명</Text></View>
            {isOwner && (
              <View style={[styles.badge, styles.badgeOwner]}>
                <Crown size={12} color={COLORS.black} strokeWidth={2.5} />
                <Text style={[styles.badgeText, styles.badgeOwnerText]}>그룹장</Text>
              </View>
            )}
          </View>
          <View style={styles.codeRow}>
            <View style={styles.codeBox}>
              <Text style={styles.codeLabel}>초대 코드</Text>
              <Text style={styles.code}>{detail.inviteCode}</Text>
            </View>
            <TouchableOpacity style={styles.codeBtn} onPress={shareCode}>
              <Share2 size={16} color={COLORS.primary} strokeWidth={2} />
              <Text style={styles.codeBtnText}>공유</Text>
            </TouchableOpacity>
            {isOwner && (
              <TouchableOpacity style={styles.codeBtn} onPress={regenerateCode}>
                <RefreshCw size={16} color={COLORS.textSecondary} strokeWidth={2} />
                <Text style={[styles.codeBtnText, { color: COLORS.textSecondary }]}>재발급</Text>
              </TouchableOpacity>
            )}
          </View>
        </View>

        {/* 모임 멤버 */}
        <Text style={styles.sectionTitle}>모임 멤버</Text>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.memberRow}>
          {activeMembers.map((m) => (
            <View key={m.memberId} style={styles.memberChip}>
              <View style={styles.memberAvatar}>
                <Text style={styles.memberAvatarText}>{m.username.slice(0, 1)}</Text>
                {m.role === 'OWNER' && (
                  <View style={styles.crown}><Crown size={10} color={COLORS.black} strokeWidth={2.5} /></View>
                )}
              </View>
              <Text style={styles.memberName} numberOfLines={1}>
                {m.username}{m.memberId === me?.memberId ? ' (나)' : ''}
              </Text>
            </View>
          ))}
          <TouchableOpacity style={styles.memberChip} onPress={shareCode} activeOpacity={0.8}>
            <View style={[styles.memberAvatar, styles.memberAvatarAdd]}>
              <UserPlus size={18} color={COLORS.primary} strokeWidth={2} />
            </View>
            <Text style={[styles.memberName, { color: COLORS.primary }]}>초대</Text>
          </TouchableOpacity>
        </ScrollView>

        {/* 출석 캘린더 */}
        <Text style={styles.sectionTitle}>출석 캘린더</Text>
        <View style={styles.calendarContainer}>
          <Calendar
            markingType="custom"
            markedDates={markedDates}
            current={`${viewYear}-${String(viewMonth).padStart(2, '0')}-01`}
            onMonthChange={(d: DateData) => {
              setViewYear(d.year);
              setViewMonth(d.month);
              loadAttendance(d.year, d.month);
            }}
            theme={{
              calendarBackground: COLORS.card,
              textSectionTitleColor: COLORS.textSecondary,
              dayTextColor: COLORS.text,
              todayTextColor: COLORS.primary,
              monthTextColor: COLORS.text,
              textDisabledColor: COLORS.textMuted,
              arrowColor: COLORS.primary,
              textMonthFontWeight: '700',
              textDayFontSize: 14,
              textMonthFontSize: 16,
            }}
            style={styles.calendar}
          />
          <Text style={styles.calendarHint}>
            칸이 진할수록 그날 운동한 멤버가 많아요 (전체 {attendance?.activeMemberCount ?? activeMembers.length}명 기준)
          </Text>
        </View>

        {/* 구성원 운동 현황 */}
        <Text style={styles.sectionTitle}>구성원 운동 현황</Text>
        <Text style={styles.sectionSub}>내 친구의 운동을 응원해봐요</Text>
        <View style={styles.sectionBody}>
          <FriendStatusList
            statuses={statuses.filter((s) => s.memberId !== me?.memberId)}
            emptyText="아직 다른 멤버가 없어요. 초대 코드를 공유해보세요"
          />
        </View>

        {/* 모임 피드 */}
        <Text style={styles.sectionTitle}>모임 피드</Text>
        <View style={styles.sectionBody}>
          {feed.length === 0 && !feedLoading ? (
            <View style={styles.emptyCard}><Text style={styles.emptyText}>아직 소식이 없어요. 운동을 완료하면 자동으로 올라와요</Text></View>
          ) : (
            feed.map((item) => {
              const p = parseFeedPayload(item);
              if (p.type === 'UNKNOWN') return null;
              const heart = item.reactionSummary.reactions.HEART ?? 0;
              const fire = item.reactionSummary.reactions.FIRE ?? 0;
              const myHeart = item.reactionSummary.myReactions.includes('HEART');
              const myFire = item.reactionSummary.myReactions.includes('FIRE');
              const isMine = p.data.memberId === me?.memberId;
              return (
                <View key={item.seq} style={styles.feedCard}>
                  <View style={styles.feedTop}>
                    <View style={styles.feedAvatar}>
                      <Text style={styles.feedAvatarText}>{p.data.username.slice(0, 1)}</Text>
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={styles.feedName}>{p.data.username}{isMine ? ' (나)' : ''}</Text>
                      <Text style={styles.feedTime}>{formatFeedTime(item.occurredAt)}</Text>
                    </View>
                    {p.type === 'SESSION_COMPLETED' ? (
                      <Dumbbell size={18} color={COLORS.primary} strokeWidth={2} />
                    ) : (
                      <UserPlus size={18} color={COLORS.textSecondary} strokeWidth={2} />
                    )}
                  </View>
                  <Text style={styles.feedText}>
                    {p.type === 'SESSION_COMPLETED'
                      ? `오늘 ${p.data.exerciseName} 운동 완료! 💪`
                      : '모임에 새로 참여했어요 👋'}
                  </Text>
                  <View style={styles.reactions}>
                    <TouchableOpacity
                      style={[styles.reaction, myHeart && styles.reactionActive]}
                      onPress={() => toggleReaction(item, 'HEART')}
                      activeOpacity={0.8}
                    >
                      <Heart size={14} color={myHeart ? COLORS.black : COLORS.textSecondary} strokeWidth={2.25} fill={myHeart ? COLORS.black : 'transparent'} />
                      <Text style={[styles.reactionText, myHeart && styles.reactionTextActive]}>{heart}</Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                      style={[styles.reaction, myFire && styles.reactionActive]}
                      onPress={() => toggleReaction(item, 'FIRE')}
                      activeOpacity={0.8}
                    >
                      <Flame size={14} color={myFire ? COLORS.black : COLORS.textSecondary} strokeWidth={2.25} fill={myFire ? COLORS.black : 'transparent'} />
                      <Text style={[styles.reactionText, myFire && styles.reactionTextActive]}>{fire}</Text>
                    </TouchableOpacity>
                  </View>
                </View>
              );
            })
          )}
          {feedLoading && <ActivityIndicator color={COLORS.primary} style={{ marginVertical: SPACING.md }} />}
          {nextBeforeSeq != null && !feedLoading && (
            <TouchableOpacity style={styles.moreBtn} onPress={() => loadFeed(nextBeforeSeq, false)}>
              <Text style={styles.moreText}>더 보기</Text>
            </TouchableOpacity>
          )}
        </View>

        <View style={{ height: 60 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: SPACING.xl,
    paddingVertical: SPACING.md,
    gap: SPACING.md,
  },
  headerTitle: { flex: 1, textAlign: 'center', fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  scroll: { paddingBottom: SPACING.xxxl },

  infoCard: {
    marginHorizontal: SPACING.xxl,
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.primary,
    padding: SPACING.lg,
    gap: SPACING.sm,
  },
  groupName: { fontSize: FONT_SIZE.xl, fontWeight: '800', color: COLORS.text },
  groupDesc: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },
  badges: { flexDirection: 'row', gap: SPACING.xs },
  badge: { flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: COLORS.surfaceLight, borderRadius: RADIUS.full, paddingHorizontal: SPACING.sm, paddingVertical: 3 },
  badgeOwner: { backgroundColor: COLORS.primary },
  badgeText: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, fontWeight: '600' },
  badgeOwnerText: { color: COLORS.black },
  codeRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm, marginTop: SPACING.xs },
  codeBox: { flex: 1, backgroundColor: COLORS.surface, borderRadius: RADIUS.md, paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm },
  codeLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  code: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.primary, letterSpacing: 2 },
  codeBtn: { alignItems: 'center', gap: 2, paddingHorizontal: SPACING.sm },
  codeBtnText: { fontSize: FONT_SIZE.xs, color: COLORS.primary, fontWeight: '600' },

  sectionTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text, paddingHorizontal: SPACING.xxl, marginTop: SPACING.xxl, marginBottom: SPACING.sm },
  sectionSub: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, paddingHorizontal: SPACING.xxl, marginTop: -SPACING.xs, marginBottom: SPACING.sm },
  sectionBody: { paddingHorizontal: SPACING.xxl },

  memberRow: { paddingHorizontal: SPACING.xxl, gap: SPACING.md },
  memberChip: { alignItems: 'center', width: 64, gap: 6 },
  memberAvatar: { width: 52, height: 52, borderRadius: 26, backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder, alignItems: 'center', justifyContent: 'center' },
  memberAvatarAdd: { borderStyle: 'dashed', borderColor: COLORS.primary, backgroundColor: COLORS.primaryDim },
  memberAvatarText: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  crown: { position: 'absolute', right: -2, top: -2, width: 18, height: 18, borderRadius: 9, backgroundColor: COLORS.primary, alignItems: 'center', justifyContent: 'center' },
  memberName: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, maxWidth: 64 },

  calendarContainer: { marginHorizontal: SPACING.xxl, borderRadius: RADIUS.lg, overflow: 'hidden', borderWidth: 1, borderColor: COLORS.cardBorder, backgroundColor: COLORS.card },
  calendar: { borderRadius: RADIUS.lg },
  calendarHint: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted, paddingHorizontal: SPACING.md, paddingBottom: SPACING.md },

  emptyCard: { backgroundColor: COLORS.card, borderRadius: RADIUS.md, borderWidth: 1, borderColor: COLORS.cardBorder, padding: SPACING.lg, alignItems: 'center' },
  emptyText: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted, textAlign: 'center' },

  feedCard: { backgroundColor: COLORS.card, borderRadius: RADIUS.md, borderWidth: 1, borderColor: COLORS.cardBorder, padding: SPACING.lg, marginBottom: SPACING.sm, gap: SPACING.sm },
  feedTop: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm },
  feedAvatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: COLORS.surfaceLight, alignItems: 'center', justifyContent: 'center' },
  feedAvatarText: { fontSize: FONT_SIZE.md, fontWeight: '800', color: COLORS.text },
  feedName: { fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.text },
  feedTime: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  feedText: { fontSize: FONT_SIZE.md, color: COLORS.text },
  reactions: { flexDirection: 'row', gap: SPACING.xs },
  reaction: { flexDirection: 'row', alignItems: 'center', gap: 4, borderRadius: RADIUS.full, borderWidth: 1, borderColor: COLORS.cardBorder, paddingHorizontal: SPACING.sm, paddingVertical: 4 },
  reactionActive: { backgroundColor: COLORS.primary, borderColor: COLORS.primary },
  reactionText: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, fontWeight: '600' },
  reactionTextActive: { color: COLORS.black },
  moreBtn: { alignItems: 'center', paddingVertical: SPACING.md },
  moreText: { fontSize: FONT_SIZE.sm, color: COLORS.primary, fontWeight: '700' },
});
