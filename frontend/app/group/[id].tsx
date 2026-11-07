import { useCallback, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  ActivityIndicator,
  Share,
  Image,
} from 'react-native';
import { Alert } from '@/utils/alert';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter, useFocusEffect } from 'expo-router';
import { Calendar, type DateData } from 'react-native-calendars';
import { ChevronLeft, ChevronUp, Share2, RefreshCw, Crown, LogOut, Dumbbell, UserPlus } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import FriendStatusList from '@/components/social/FriendStatusList';
import CheerModal from '@/components/social/CheerModal';
import { groupService } from '@/services/groupService';
import { API_BASE_URL } from '@/services/api';
import { useAuthStore } from '@/stores/authStore';
import {
  parseFeedPayload,
  type GroupAttendanceCalendar,
  type GroupDetail,
  type GroupFeedItem,
  type MemberAttendanceStatus,
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

  // 탈퇴 — 서버 규칙을 미리 알려준다. 혼자 남은 그룹장이 나가면 모임 자체가 지워져 초대 코드도 사라진다
  // (그 코드로 다시 들어오려 하면 «유효하지 않은 초대 코드»). 일반 멤버는 같은 코드로 다시 들어올 수 있다.
  const leave = () => {
    const others = activeMembers.filter((m) => m.memberId !== me?.memberId);
    if (isOwner && others.length > 0) {
      Alert.alert('그룹장은 바로 탈퇴할 수 없어요', '멤버 사진을 눌러 다른 멤버에게 그룹장을 넘긴 뒤 탈퇴해주세요.');
      return;
    }
    const message = isOwner
      ? '혼자 남은 그룹장이 탈퇴하면 모임이 삭제되고 초대 코드도 사라져요. 다시 들어올 수 없어요. 탈퇴할까요?'
      : '탈퇴해도 같은 초대 코드로 다시 들어올 수 있어요. 남긴 글과 응원은 남아요. 탈퇴할까요?';
    Alert.alert(isOwner ? '모임 삭제' : '모임 탈퇴', message, [
      { text: '취소', style: 'cancel' },
      {
        text: isOwner ? '삭제' : '탈퇴',
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

  // 그룹장 넘기기 — 그룹장만, 다른 멤버의 사진을 눌러서
  const transferTo = (memberId: number, username: string) => {
    if (!isOwner || memberId === me?.memberId) return;
    Alert.alert('그룹장 넘기기', `${username}님에게 그룹장을 넘길까요? 나는 일반 멤버가 돼요.`, [
      { text: '취소', style: 'cancel' },
      {
        text: '넘기기',
        onPress: async () => {
          try {
            await groupService.transferOwnership(groupId, memberId);
            loadAll();
          } catch (e: any) {
            Alert.alert('넘기기 실패', errorText(e, '그룹장을 넘기지 못했어요.'));
          }
        },
      },
    ]);
  };

  // 응원 한마디 — 공유 글 하나에 회원당 한 줄. 응답(그 글의 응원 전부)으로 그 글만 갈아끼운다
  const [cheerTarget, setCheerTarget] = useState<GroupFeedItem | null>(null);
  const [cheerSending, setCheerSending] = useState(false);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const sendCheer = async (message: string) => {
    if (!cheerTarget) return;
    setCheerSending(true);
    try {
      const res = await groupService.cheerEvent(groupId, cheerTarget.seq, message);
      setFeed((prev) => prev.map((f) => (f.seq === cheerTarget.seq ? { ...f, cheers: res.data } : f)));
      setCheerTarget(null);
    } catch (e: any) {
      Alert.alert('응원 실패', errorText(e, '응원을 보내지 못했어요.'));
    } finally {
      setCheerSending(false);
    }
  };

  const removeMyCheer = (item: GroupFeedItem) => {
    Alert.alert('응원 지우기', '내 응원을 지울까요?', [
      { text: '취소', style: 'cancel' },
      {
        text: '지우기',
        style: 'destructive',
        onPress: async () => {
          try {
            const res = await groupService.uncheerEvent(groupId, item.seq);
            setFeed((prev) => prev.map((f) => (f.seq === item.seq ? { ...f, cheers: res.data } : f)));
          } catch (e: any) {
            Alert.alert('지우기 실패', errorText(e, '응원을 지우지 못했어요.'));
          }
        },
      },
    ]);
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
            <TouchableOpacity
              key={m.memberId}
              style={styles.memberChip}
              onPress={() => transferTo(m.memberId, m.username)}
              disabled={!isOwner || m.memberId === me?.memberId}
              activeOpacity={0.7}
            >
              <View style={styles.memberAvatar}>
                <Text style={styles.memberAvatarText}>{m.username.slice(0, 1)}</Text>
                {m.role === 'OWNER' && (
                  <View style={styles.crown}><Crown size={10} color={COLORS.black} strokeWidth={2.5} /></View>
                )}
              </View>
              <Text style={styles.memberName} numberOfLines={1}>
                {m.username}{m.memberId === me?.memberId ? ' (나)' : ''}
              </Text>
            </TouchableOpacity>
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
        <Text style={styles.sectionSub}>오늘 아직 운동 안 한 친구를 재촉해봐요</Text>
        <View style={styles.sectionBody}>
          <FriendStatusList
            statuses={statuses}
            meId={me?.memberId}
            emptyText="아직 멤버가 없어요. 초대 코드를 공유해보세요"
          />
        </View>

        {/* 모임 피드 */}
        <Text style={styles.sectionTitle}>모임 피드</Text>
        <View style={styles.sectionBody}>
          {feed.length === 0 && !feedLoading ? (
            <View style={styles.emptyCard}>
              <Text style={styles.emptyText}>아직 소식이 없어요. 운동을 마치고 보고서에서 «모임 피드에 공유하기» 를 눌러보세요</Text>
            </View>
          ) : (
            feed.map((item) => {
              const p = parseFeedPayload(item);
              if (p.type === 'UNKNOWN') return null;

              // 운동 완료 자동 기록 · 새 멤버 — 한 줄 소식으로 작게
              if (p.type !== 'SESSION_SHARED') {
                return (
                  <View key={item.seq} style={styles.activityRow}>
                    {p.type === 'SESSION_COMPLETED' ? (
                      <Dumbbell size={14} color={COLORS.primary} strokeWidth={2} />
                    ) : (
                      <UserPlus size={14} color={COLORS.textSecondary} strokeWidth={2} />
                    )}
                    <Text style={styles.activityText} numberOfLines={1}>
                      <Text style={styles.activityName}>{p.data.username}</Text>
                      {p.type === 'SESSION_COMPLETED' ? `님이 ${p.data.exerciseName} 운동을 마쳤어요` : '님이 모임에 들어왔어요 👋'}
                    </Text>
                    <Text style={styles.activityTime}>{formatFeedTime(item.occurredAt).slice(5)}</Text>
                  </View>
                );
              }

              // 직접 공유한 운동 — 사진 · 한마디 · 응원
              const d = p.data;
              const isMine = d.memberId === me?.memberId;
              const myCheer = item.cheers.find((c) => c.memberId === me?.memberId);
              const open = expanded.has(item.seq);
              const cheers = open ? item.cheers : item.cheers.slice(0, 2);
              return (
                <View key={item.seq} style={styles.feedCard}>
                  <View style={styles.feedTop}>
                    <View style={styles.feedAvatar}>
                      <Text style={styles.feedAvatarText}>{d.username.slice(0, 1)}</Text>
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={styles.feedName}>{d.username}{isMine ? ' (나)' : ''}</Text>
                      <Text style={styles.feedTime}>{formatFeedTime(item.occurredAt)}</Text>
                    </View>
                    {!isMine && (
                      <TouchableOpacity style={styles.cheerBtn} onPress={() => setCheerTarget(item)} activeOpacity={0.8}>
                        <Text style={styles.cheerBtnText}>{myCheer ? '응원 바꾸기' : '응원보내기'}</Text>
                      </TouchableOpacity>
                    )}
                  </View>
                  {!!d.photoUrl && (
                    <Image source={{ uri: `${API_BASE_URL}${d.photoUrl}` }} style={styles.feedPhoto} resizeMode="cover" />
                  )}
                  <Text style={styles.feedText}>{d.caption || `오늘 ${d.exerciseName} ${d.totalReps}회 완료! 💪`}</Text>
                  <Text style={styles.feedStats}>{d.exerciseName} · {d.totalReps}회 · {d.workoutMinutes}분</Text>
                  {item.cheers.length > 0 && (
                    <View style={styles.cheerChips}>
                      {cheers.map((c) => {
                        const mine = c.memberId === me?.memberId;
                        return (
                          <TouchableOpacity
                            key={c.memberId}
                            style={[styles.cheerChip, mine && styles.cheerChipMine]}
                            onLongPress={mine ? () => removeMyCheer(item) : undefined}
                            disabled={!mine}
                            activeOpacity={0.8}
                          >
                            <Text style={styles.cheerChipName}>{c.username}</Text>
                            <Text style={styles.cheerChipMsg}>{c.message}</Text>
                          </TouchableOpacity>
                        );
                      })}
                    </View>
                  )}
                  {item.cheers.length > 2 && (
                    <TouchableOpacity
                      style={styles.expand}
                      onPress={() => setExpanded((prev) => {
                        const next = new Set(prev);
                        if (next.has(item.seq)) next.delete(item.seq); else next.add(item.seq);
                        return next;
                      })}
                    >
                      {open ? (
                        <ChevronUp size={18} color={COLORS.textMuted} strokeWidth={2} />
                      ) : (
                        <Text style={styles.expandText}>응원 {item.cheers.length - 2}개 더 보기</Text>
                      )}
                    </TouchableOpacity>
                  )}
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

      <CheerModal
        visible={!!cheerTarget}
        targetName={cheerTarget ? (parseFeedPayload(cheerTarget) as { data?: { username?: string } }).data?.username ?? '' : ''}
        sending={cheerSending}
        onClose={() => setCheerTarget(null)}
        onSend={sendCheer}
      />
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
  activityRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm, paddingVertical: SPACING.sm, paddingHorizontal: SPACING.xs, marginBottom: SPACING.xs },
  activityText: { flex: 1, fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },
  activityName: { fontWeight: '700', color: COLORS.text },
  activityTime: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  cheerBtn: { backgroundColor: COLORS.primary, borderRadius: RADIUS.full, paddingHorizontal: SPACING.md, paddingVertical: 6 },
  cheerBtnText: { fontSize: FONT_SIZE.xs, fontWeight: '800', color: COLORS.black },
  feedPhoto: { width: '100%', aspectRatio: 4 / 3, borderRadius: RADIUS.md, backgroundColor: COLORS.surfaceLight },
  feedStats: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted, marginTop: -4 },
  cheerChips: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.xs },
  cheerChip: { flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: RADIUS.full, borderWidth: 1, borderColor: COLORS.cardBorder, backgroundColor: COLORS.surface, paddingHorizontal: SPACING.md, paddingVertical: 5, maxWidth: '100%' },
  cheerChipMine: { borderColor: COLORS.primary },
  cheerChipName: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  cheerChipMsg: { fontSize: FONT_SIZE.sm, color: COLORS.text, flexShrink: 1 },
  expand: { alignItems: 'center', paddingTop: SPACING.xs },
  expandText: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  moreBtn: { alignItems: 'center', paddingVertical: SPACING.md },
  moreText: { fontSize: FONT_SIZE.sm, color: COLORS.primary, fontWeight: '700' },
});
