import { View, Text, StyleSheet, TouchableOpacity, ScrollView } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter, useFocusEffect } from 'expo-router';
import { Calendar, type DateData } from 'react-native-calendars';
import { useCallback, useEffect, useState } from 'react';
import {
  CircleUser,
  Flame,
  Target,
  Trophy,
  Camera,
  Ruler,
  PersonStanding,
  Lightbulb,
  Ban,
  ChevronRight,
  Bell,
  type LucideIcon,
} from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import { Alert } from '@/utils/alert';
import { reportService } from '@/services/reportService';
import { goalService } from '@/services/goalService';
import { useAuthStore } from '@/stores/authStore';
import type { CalendarMainResponse, CalendarDay, DailyActivityResponse } from '@/types/report';
import type { WorkoutGoal } from '@/types/goal';
import FriendStatusList from '@/components/social/FriendStatusList';
import { friendService } from '@/services/friendService';
import { notificationService } from '@/services/notificationService';
import type { MemberAttendanceStatus } from '@/types/social';
import { syncColor } from '@/constants/sync';

// 목록·달력은 여러 종목이 섞여 종목별 기준을 알 수 없어 기본 판정 기준(80 / 60)으로 색을 낸다 — constants/sync.ts
const getSyncColor = (rate: number) => syncColor(rate);

// 백엔드 records 를 react-native-calendars 의 markedDates 형식으로 변환
function buildMarkedDates(records: CalendarDay[]): Record<string, any> {
  const marked: Record<string, any> = {};
  for (const r of records) {
    if (!r.hasRecord) continue;
    // 정석(≥ 80) 라임 / 교정 필요(≥ 60) 주황 / 위험 빨강 — 기록만 있고 점수가 없는 날은 라임
    const sync = r.dailyAvgSyncRate;
    const dotColor = sync == null ? COLORS.primary : syncColor(sync);
    marked[r.date] = { marked: true, dotColor };
  }
  return marked;
}

// «오늘» 은 로컬 날짜로 만든다 — toISOString() 은 UTC 라 한국 밤 9시 이후엔 내일로 잡힌다
function localDateString(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export default function HomeScreen() {
  const router = useRouter();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const today = localDateString(new Date());
  const [selectedDate, setSelectedDate] = useState('');

  // 캘린더가 가리키는 연/월 (사용자가 ◀▶ 눌러 바꿈)
  const initialDate = new Date();
  const [viewYear, setViewYear] = useState(initialDate.getFullYear());
  const [viewMonth, setViewMonth] = useState(initialDate.getMonth() + 1);

  const [data, setData] = useState<CalendarMainResponse | null>(null);
  // 선택 날짜의 운동 목록 (GET /reports/daily)
  const [daily, setDaily] = useState<DailyActivityResponse | null>(null);
  const [dailyLoading, setDailyLoading] = useState(false);
  const [friends, setFriends] = useState<MemberAttendanceStatus[]>([]);

  // 나의 운동 약속 — 최근 7일 완료 세션 수 목표 (GET/POST/PATCH /goals)
  const [goal, setGoal] = useState<WorkoutGoal | null>(null);
  const [goalLoading, setGoalLoading] = useState(false);
  const [goalSaving, setGoalSaving] = useState(false);
  const [goalError, setGoalError] = useState(false);
  const [goalEditing, setGoalEditing] = useState(false);

  // 화면 포커스 / 연·월 변경마다 캘린더 데이터 재조회
  useFocusEffect(
    useCallback(() => {
      reportService
        .getCalendar(viewYear, viewMonth)
        .then((res) => setData(res.data))
        .catch((e) => {
          console.error('[calendar] status=', e.response?.status, 'data=', e.response?.data);
        });
    }, [viewYear, viewMonth]),
  );

  // 내 모임 친구 운동 현황 — 화면 포커스마다 재조회 (재촉·응원 뒤 돌아오면 최신 상태)
  useFocusEffect(
    useCallback(() => {
      friendService
        .getStatuses()
        .then((res) => setFriends(res.data))
        .catch((e) => console.warn('[friends] status=', e?.response?.status));
    }, []),
  );

  // 운동 약속 — 로그인 상태에서만 조회
  useFocusEffect(
    useCallback(() => {
      if (!isAuthenticated) {
        setGoal(null);
        setGoalError(false);
        setGoalLoading(false);
        return;
      }
      let active = true;
      setGoalLoading(true);
      goalService.getGoals()
        .then((res) => {
          if (!active) return;
          setGoal(res.data.find((item) => item.goalType === 'WEEKLY_SESSIONS') ?? null);
          setGoalError(false);
        })
        .catch(() => { if (active) setGoalError(true); })
        .finally(() => { if (active) setGoalLoading(false); });
      return () => { active = false; };
    }, [isAuthenticated]),
  );

  const saveGoal = async (targetValue: number) => {
    if (!isAuthenticated) {
      router.push('/(auth)/login');
      return;
    }
    if (goalSaving) return;
    setGoalSaving(true);
    try {
      const response = goal
        ? await goalService.updateGoal(goal.id, targetValue)
        : await goalService.createWeeklySessionsGoal(targetValue);
      setGoal(response.data);
      setGoalError(false);
      setGoalEditing(false);
    } catch (error: any) {
      Alert.alert('목표를 저장하지 못했습니다', error?.response?.data?.message ?? '연결을 확인하고 다시 시도해주세요.');
    } finally {
      setGoalSaving(false);
    }
  };

  const retryGoal = async () => {
    setGoalLoading(true);
    try {
      const response = await goalService.getGoals();
      setGoal(response.data.find((item) => item.goalType === 'WEEKLY_SESSIONS') ?? null);
      setGoalError(false);
    } catch {
      setGoalError(true);
    } finally {
      setGoalLoading(false);
    }
  };

  // 종 아이콘 배지 — 안 읽은 알림 수. 홈에 있는 동안 30초마다, 알림함에서 돌아올 때 바로 다시 센다
  // (푸시·소켓이 아직 없어서 새 재촉·응원을 알아챌 길이 이것뿐이다).
  const [unread, setUnread] = useState(0);
  useFocusEffect(
    useCallback(() => {
      const refresh = () =>
        notificationService
          .unreadCount()
          .then((res) => setUnread(res.data.count))
          .catch(() => {});
      refresh();
      const timer = setInterval(refresh, 30000);
      return () => clearInterval(timer);
    }, []),
  );

  // 선택 날짜 변경 시 그 날의 운동 목록 조회 (없으면 오늘 자동 조회)
  useEffect(() => {
    const targetDate = selectedDate || today;
    setDailyLoading(true);
    reportService
      .getDaily(targetDate)
      .then((res) => setDaily(res.data))
      .catch((e) => {
        console.warn('[daily] 에러 status=', e?.response?.status, 'data=', JSON.stringify(e?.response?.data));
        setDaily(null);
      })
      .finally(() => setDailyLoading(false));
  }, [selectedDate, today]);

  const baseMarked = data ? buildMarkedDates(data.records) : {};
  const markedDates = {
    ...baseMarked,
    ...(selectedDate
      ? {
          [selectedDate]: {
            ...(baseMarked[selectedDate] ?? {}),
            selected: true,
            selectedColor: COLORS.primary,
          },
        }
      : {}),
  };

  // 날짜 클릭 시 selectedDate 만 갱신.
  // 실제 일별 조회는 위쪽 useEffect 가 selectedDate 변화로 트리거.
  const handleDayPress = useCallback(
    (day: DateData) => setSelectedDate(day.dateString),
    [],
  );

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={styles.scrollContent}>
        {/* 헤더 */}
        <View style={styles.header}>
          <View>
            <Text style={styles.appTitle}>ShadowFit</Text>
            <Text style={styles.appSubtitle}>AI 자세 교정 트레이너</Text>
          </View>
          <View style={styles.headerIcons}>
            <TouchableOpacity onPress={() => router.push('/notifications' as any)} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
              <Bell size={24} color={unread > 0 ? COLORS.text : COLORS.textSecondary} strokeWidth={1.75} />
              {unread > 0 && (
                <View style={styles.badge}>
                  <Text style={styles.badgeText}>{unread > 9 ? '9+' : unread}</Text>
                </View>
              )}
            </TouchableOpacity>
            <TouchableOpacity onPress={() => router.push('/(tabs)/mypage')}>
              <CircleUser size={28} color={COLORS.textSecondary} strokeWidth={1.75} />
            </TouchableOpacity>
          </View>
        </View>

        {/* 상단 통계 카드 - 백엔드 CalendarMainResponse 의 monthly* 사용 */}
        <View style={styles.statsRow}>
          <StatCard
            Icon={Flame}
            value={`${data?.monthlyExerciseDays ?? 0}일`}
            label="이번 달"
          />
          <StatCard
            Icon={Target}
            value={`${data?.totalAvgSyncRate ?? 0}%`}
            label="평균 싱크로율"
            highlight
          />
          <StatCard
            Icon={Trophy}
            value={`${data?.consecutiveDays ?? 0}일`}
            label="연속 기록"
          />
        </View>

        {/* 나의 운동 약속 — 최근 7일 완료 세션 수 목표 */}
        <View style={styles.goalCard}>
          <View style={styles.goalHeading}>
            <Target size={20} color={COLORS.primary} strokeWidth={2} />
            <Text style={styles.goalEyebrow}>나의 운동 약속</Text>
          </View>
          {goalLoading ? (
            <Text style={styles.goalDescription}>목표를 불러오는 중...</Text>
          ) : goal ? (
            <>
              <Text style={styles.goalTitle}>최근 7일 {goal.currentValue} / {goal.targetValue}회 완료</Text>
              <View style={styles.goalTrack}>
                <View style={[styles.goalFill, { width: `${Math.min(100, (goal.currentValue / goal.targetValue) * 100)}%` }]} />
              </View>
              <Text style={styles.goalDescription}>
                {goal.status === 'ACHIEVED' ? '이번 목표를 채웠어요! 다음 운동도 내 페이스로 이어가요.' : '짧게 운동해도 한 번의 완료로 기록돼요.'}
              </Text>
            </>
          ) : (
            <>
              <Text style={styles.goalTitle}>{isAuthenticated ? '최근 7일 목표를 정해볼까요?' : '운동 약속을 만들어보세요'}</Text>
              <Text style={styles.goalDescription}>{goalError ? '목표를 불러오지 못했습니다. 연결을 확인해주세요.' : '매일 운동할 필요는 없어요. 내 일정에 맞춰 시작하세요.'}</Text>
            </>
          )}
          {goalError ? (
            <TouchableOpacity accessibilityRole="button" onPress={() => void retryGoal()} style={styles.goalEditButton}>
              <Text style={styles.goalEditText}>다시 불러오기</Text>
            </TouchableOpacity>
          ) : isAuthenticated && (goalEditing || !goal) ? (
            <View style={styles.goalOptions}>
              {[2, 3, 4].map((target) => (
                <TouchableOpacity
                  key={target}
                  accessibilityRole="button"
                  disabled={goalSaving || goalLoading}
                  onPress={() => saveGoal(target)}
                  style={[styles.goalOption, goal?.targetValue === target && styles.goalOptionSelected]}
                >
                  <Text style={styles.goalOptionText}>{target}회</Text>
                </TouchableOpacity>
              ))}
            </View>
          ) : goal ? (
            <TouchableOpacity accessibilityRole="button" onPress={() => setGoalEditing(true)} style={styles.goalEditButton}>
              <Text style={styles.goalEditText}>목표 변경</Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity accessibilityRole="button" onPress={() => router.push('/(auth)/login')} style={styles.goalEditButton}>
              <Text style={styles.goalEditText}>로그인하고 시작하기</Text>
            </TouchableOpacity>
          )}
          <Text style={styles.goalFootnote}>완료한 운동 세션만 셉니다. 쉬는 날에도 목표 설정은 유지돼요.</Text>
        </View>

        {/* 내 모임 친구 운동 현황 — GET /friends (오늘 완료 → 진행 중 → 기록 없음 순) */}
        <View style={styles.friendsSection}>
          <View style={styles.friendsHeader}>
            <View>
              <Text style={styles.friendsTitle}>최신 모임 운동 현황</Text>
              <Text style={styles.friendsSub}>내 친구의 운동을 응원해봐요</Text>
            </View>
            <TouchableOpacity onPress={() => router.push('/(tabs)/groups' as any)} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
              <Text style={styles.friendsMore}>모임 보기</Text>
            </TouchableOpacity>
          </View>
          <FriendStatusList
            statuses={friends}
            limit={3}
            emptyText="모임에 참여하면 친구들의 운동 현황이 여기 보여요"
          />
          {friends.length > 3 && (
            <TouchableOpacity style={styles.friendsAll} onPress={() => router.push('/(tabs)/groups' as any)}>
              <Text style={styles.friendsAllText}>친구 {friends.length}명 전체 보기</Text>
              <ChevronRight size={14} color={COLORS.primary} strokeWidth={2} />
            </TouchableOpacity>
          )}
        </View>

        {/* 캘린더 */}
        <View style={styles.calendarContainer}>
          <Calendar
            markedDates={markedDates}
            onDayPress={handleDayPress}
            onMonthChange={(d: DateData) => {
              setViewYear(d.year);
              setViewMonth(d.month);
              setSelectedDate('');
            }}
            theme={{
              calendarBackground: COLORS.card,
              textSectionTitleColor: COLORS.textSecondary,
              dayTextColor: COLORS.text,
              todayTextColor: COLORS.primary,
              selectedDayTextColor: COLORS.black,
              monthTextColor: COLORS.text,
              textDisabledColor: COLORS.textMuted,
              arrowColor: COLORS.primary,
              textMonthFontWeight: '700',
              textDayFontSize: 14,
              textMonthFontSize: 16,
            }}
            style={styles.calendar}
          />
        </View>

        {/* 선택 날짜 정보 + 일별 운동 목록 */}
        <View style={styles.dateInfo}>
          <Text style={styles.dateInfoText}>
            {selectedDate
              ? `${new Date(selectedDate).getMonth() + 1}월 ${new Date(selectedDate).getDate()}일 ${['일', '월', '화', '수', '목', '금', '토'][new Date(selectedDate).getDay()]}요일`
              : `${new Date().getMonth() + 1}월 ${new Date().getDate()}일 ${['일', '월', '화', '수', '목', '금', '토'][new Date().getDay()]}요일`}
          </Text>
          {dailyLoading ? (
            <Text style={styles.noRecordText}>불러오는 중...</Text>
          ) : (daily?.totalWorkouts ?? 0) === 0 ? (
            <Text style={styles.noRecordText}>운동 기록이 없습니다</Text>
          ) : (
            <Text style={styles.dailyCountText}>
              총 {daily?.totalWorkouts ?? 0}개의 운동
            </Text>
          )}
        </View>

        {(daily?.sessions ?? []).map((s) => (
          <TouchableOpacity
            key={s.sessionId}
            style={styles.dailyCard}
            activeOpacity={0.7}
            onPress={() => router.push(`/report/${s.sessionId}` as any)}
          >
            <View style={styles.dailyCardLeft}>
              <Text style={styles.dailyCardName}>{s.exerciseName}</Text>
              <Text style={styles.dailyCardSets}>{s.setSummary}</Text>
            </View>
            <View style={styles.dailyCardRight}>
              {/* 횟수만 재는 종목(scoreAvailable=false)은 싱크로율 대신 «횟수 기록» */}
              {s.scoreAvailable === false ? (
                <Text style={styles.dailyCardCountOnly}>횟수 기록</Text>
              ) : (
                <Text style={[styles.dailyCardSync, { color: getSyncColor(s.syncRate) }]}>
                  {Math.round(s.syncRate)}%
                </Text>
              )}
              <ChevronRight size={14} color={COLORS.textMuted} strokeWidth={2} />
            </View>
          </TouchableOpacity>
        ))}

        {/* 촬영 가이드 */}
        <View style={styles.guideBox}>
          <View style={styles.guideHeaderRow}>
            <Camera size={16} color={COLORS.text} strokeWidth={2} />
            <Text style={styles.guideHeader}>운동 촬영 가이드</Text>
          </View>
          <GuideItem Icon={Ruler} text="스쿼트는 정면 기준 45° · 런지는 측면에 카메라 고정" />
          <GuideItem Icon={PersonStanding} text="머리부터 발목까지 담고 시작·종료 자세를 2초 유지" />
          <GuideItem Icon={Lightbulb} text="밝은 조명에서 관절이 선명하게 보이도록 촬영" />
          <GuideItem Icon={Ban} text="거울 반사, 무릎을 가리는 기구, 여러 사람이 보이는 환경은 피해주세요" />
        </View>

        {/* 운동 시작 버튼 */}
        <Button
          title="▷  운동 시작하기"
          onPress={() => router.push('/(tabs)/exercise')}
          style={styles.startBtn}
        />

        <View style={{ height: 80 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

function StatCard({ Icon, value, label, highlight }: {
  Icon: LucideIcon;
  value: string;
  label: string;
  highlight?: boolean;
}) {
  return (
    <View style={[styles.statCard, highlight && styles.statCardHighlight]}>
      <Icon
        size={20}
        color={highlight ? COLORS.primary : COLORS.textSecondary}
        strokeWidth={2}
      />
      <Text style={[styles.statValue, highlight && styles.statValueHighlight]}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

function GuideItem({ Icon, text }: { Icon: LucideIcon; text: string }) {
  return (
    <View style={styles.guideItem}>
      <Icon size={16} color={COLORS.primary} strokeWidth={2} />
      <Text style={styles.guideText}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
    paddingVertical: SPACING.md,
  },
  appTitle: { fontSize: FONT_SIZE.xl, fontWeight: '800', color: COLORS.primary },
  appSubtitle: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  statsRow: {
    flexDirection: 'row',
    paddingHorizontal: SPACING.xxl,
    gap: SPACING.md,
    marginVertical: SPACING.md,
  },
  statCard: {
    flex: 1,
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.md,
    alignItems: 'center',
  },
  statCardHighlight: { borderColor: COLORS.primary },
  statValue: { fontSize: FONT_SIZE.xl, fontWeight: '800', color: COLORS.text, marginTop: 4 },
  statValueHighlight: { color: COLORS.primary },
  statLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  // 나의 운동 약속
  goalCard: {
    marginHorizontal: SPACING.xxl,
    marginBottom: SPACING.lg,
    padding: SPACING.xl,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.primary,
    backgroundColor: COLORS.card,
  },
  goalHeading: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm },
  goalEyebrow: { color: COLORS.primary, fontSize: FONT_SIZE.sm, fontWeight: '700' },
  goalTitle: { color: COLORS.text, fontSize: FONT_SIZE.xl, fontWeight: '800', marginTop: SPACING.md },
  goalDescription: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 20, marginTop: SPACING.sm },
  goalTrack: { height: 9, borderRadius: RADIUS.full, backgroundColor: COLORS.surfaceLight, overflow: 'hidden', marginTop: SPACING.lg },
  goalFill: { height: '100%', backgroundColor: COLORS.primary, borderRadius: RADIUS.full },
  goalOptions: { flexDirection: 'row', gap: SPACING.sm, marginTop: SPACING.lg },
  goalOption: { flex: 1, borderWidth: 1, borderColor: COLORS.cardBorder, borderRadius: RADIUS.sm, paddingVertical: SPACING.md, alignItems: 'center' },
  goalOptionSelected: { borderColor: COLORS.primary, backgroundColor: COLORS.primaryDim },
  goalOptionText: { color: COLORS.text, fontSize: FONT_SIZE.md, fontWeight: '700' },
  goalEditButton: { alignSelf: 'flex-start', marginTop: SPACING.md, paddingVertical: SPACING.sm },
  goalEditText: { color: COLORS.primary, fontSize: FONT_SIZE.sm, fontWeight: '700' },
  goalFootnote: { color: COLORS.textMuted, fontSize: FONT_SIZE.xs, marginTop: SPACING.md, lineHeight: 17 },

  // 친구 운동 현황
  friendsSection: { paddingHorizontal: SPACING.xxl, marginBottom: SPACING.lg },
  friendsHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: SPACING.sm },
  friendsTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  friendsSub: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },
  friendsMore: { fontSize: FONT_SIZE.xs, fontWeight: '700', color: COLORS.primary },
  friendsAll: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, paddingVertical: SPACING.sm },
  friendsAllText: { fontSize: FONT_SIZE.xs, fontWeight: '700', color: COLORS.primary },
  headerIcons: { flexDirection: 'row', alignItems: 'center', gap: SPACING.lg },
  badge: {
    position: 'absolute',
    top: -5,
    right: -7,
    minWidth: 17,
    height: 17,
    borderRadius: 9,
    paddingHorizontal: 4,
    backgroundColor: COLORS.error,
    borderWidth: 1.5,
    borderColor: COLORS.background,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeText: { fontSize: 10, fontWeight: '800', color: COLORS.white },

  calendarContainer: {
    marginHorizontal: SPACING.xxl,
    borderRadius: RADIUS.lg,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  calendar: { borderRadius: RADIUS.lg },

  dateInfo: { paddingHorizontal: SPACING.xxl, paddingVertical: SPACING.lg },
  dateInfoText: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },
  noRecordText: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted, marginTop: 4 },
  dailyCountText: { fontSize: FONT_SIZE.sm, color: COLORS.primary, fontWeight: '700', marginTop: 4 },

  // 일별 운동 카드
  dailyCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
    marginHorizontal: SPACING.xxl,
    marginBottom: SPACING.sm,
  },
  dailyCardLeft: { flex: 1 },
  dailyCardName: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  dailyCardSets: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },
  dailyCardRight: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm },
  dailyCardSync: { fontSize: FONT_SIZE.lg, fontWeight: '800' },
  dailyCardCountOnly: { fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.textSecondary },

  scrollContent: {
    paddingBottom: SPACING.xxxl,
  },

  // 촬영 가이드
  guideBox: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    marginHorizontal: SPACING.xxl,
    padding: SPACING.lg,
    gap: SPACING.sm,
  },
  guideHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
    marginBottom: SPACING.xs,
  },
  guideHeader: {
    fontSize: FONT_SIZE.sm,
    fontWeight: '700',
    color: COLORS.text,
  },
  guideItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
  },
  guideText: {
    flex: 1,
    fontSize: FONT_SIZE.sm,
    color: COLORS.textSecondary,
    lineHeight: 20,
  },

  startBtn: {
    marginHorizontal: SPACING.xxl,
    marginTop: SPACING.xl,
  },
});
