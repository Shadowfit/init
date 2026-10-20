import { View, Text, StyleSheet, TouchableOpacity, ScrollView, Alert } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter, useFocusEffect } from 'expo-router';
import { Calendar, type DateData } from 'react-native-calendars';
import { useCallback, useEffect, useState } from 'react';
import {
  CircleUser,
  Target,
  Users,
  Camera,
  Ruler,
  PersonStanding,
  Lightbulb,
  Ban,
  ChevronRight,
  type LucideIcon,
} from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import { reportService } from '@/services/reportService';
import { goalService } from '@/services/goalService';
import { socialService } from '@/services/socialService';
import { useAuthStore } from '@/stores/authStore';
import type { CalendarMainResponse, CalendarDay, DailyActivityResponse } from '@/types/report';
import type { WorkoutGoal } from '@/types/goal';
import type { FriendAttendance } from '@/types/social';

// 백엔드 records 를 react-native-calendars 의 markedDates 형식으로 변환
function buildMarkedDates(records: CalendarDay[]): Record<string, any> {
  const marked: Record<string, any> = {};
  for (const r of records) {
    if (!r.hasRecord) continue;
    marked[r.date] = { marked: true, dotColor: COLORS.primary };
  }
  return marked;
}

export default function HomeScreen() {
  const router = useRouter();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const localToday = new Date();
  const today = `${localToday.getFullYear()}-${String(localToday.getMonth() + 1).padStart(2, '0')}-${String(localToday.getDate()).padStart(2, '0')}`;
  const [selectedDate, setSelectedDate] = useState('');

  // 캘린더가 가리키는 연/월 (사용자가 ◀▶ 눌러 바꿈)
  const initialDate = new Date();
  const [viewYear, setViewYear] = useState(initialDate.getFullYear());
  const [viewMonth, setViewMonth] = useState(initialDate.getMonth() + 1);

  const [data, setData] = useState<CalendarMainResponse | null>(null);
  // 선택 날짜의 운동 목록 (GET /reports/daily)
  const [daily, setDaily] = useState<DailyActivityResponse | null>(null);
  const [dailyLoading, setDailyLoading] = useState(false);
  const [goal, setGoal] = useState<WorkoutGoal | null>(null);
  const [goalLoading, setGoalLoading] = useState(false);
  const [goalSaving, setGoalSaving] = useState(false);
  const [goalError, setGoalError] = useState(false);
  const [goalEditing, setGoalEditing] = useState(false);
  const [friends, setFriends] = useState<FriendAttendance[]>([]);

  useFocusEffect(
    useCallback(() => {
      if (!isAuthenticated) {
        setGoal(null);
        setGoalError(false);
        setGoalLoading(false);
        setFriends([]);
        return;
      }
      let active = true;
      setGoalLoading(true);
      Promise.allSettled([goalService.getGoals(), socialService.getFriends()]).then(([goals, friendStatuses]) => {
        if (!active) return;
        if (goals.status === 'fulfilled') {
          setGoal(goals.value.data.find((item) => item.goalType === 'WEEKLY_SESSIONS') ?? null);
          setGoalError(false);
        } else {
          setGoalError(true);
        }
        if (friendStatuses.status === 'fulfilled') setFriends(friendStatuses.value.data);
        setGoalLoading(false);
      });
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

  // 선택 날짜 변경 시 그 날의 운동 목록 조회 (없으면 오늘 자동 조회)
  useEffect(() => {
    const targetDate = selectedDate || today;
    setDailyLoading(true);
    console.log('[daily] 요청 date=', targetDate);
    reportService
      .getDaily(targetDate)
      .then((res) => {
        console.log('[daily] 응답', JSON.stringify(res.data));
        setDaily(res.data);
      })
      .catch((e) => {
        console.warn(
          '[daily] 에러 status=', e?.response?.status,
          'data=', JSON.stringify(e?.response?.data),
        );
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
            <Text style={styles.appSubtitle}>친구와 함께 꾸준히 운동해요</Text>
          </View>
          <TouchableOpacity onPress={() => router.push('/(tabs)/mypage')}>
            <CircleUser size={28} color={COLORS.textSecondary} strokeWidth={1.75} />
          </TouchableOpacity>
        </View>

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

        <View style={styles.statsRow}>
          <StatCard Icon={Camera} value={data ? `${data.monthlyExerciseDays}일` : '—'} label={`${viewMonth}월 기록일`} />
          <StatCard Icon={Target} value={goal ? `${goal.currentValue}회` : '—'} label="최근 7일 완료" />
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
            onPress={() => s.scoreAvailable === false
              ? Alert.alert(s.exerciseName, `${s.setSummary}\n자세 점수는 측정하지 않았습니다.`)
              : router.push(`/report/${s.sessionId}` as any)}
          >
            <View style={styles.dailyCardLeft}>
              <Text style={styles.dailyCardName}>{s.exerciseName}</Text>
              <Text style={styles.dailyCardSets}>{s.setSummary}</Text>
            </View>
            <View style={styles.dailyCardRight}>
              <Text style={styles.dailyCardSync}>{s.scoreAvailable === false ? '횟수 기록' : '기록 보기'}</Text>
              <ChevronRight size={14} color={COLORS.textMuted} strokeWidth={2} />
            </View>
          </TouchableOpacity>
        ))}

        <TouchableOpacity
          style={styles.friendsCard}
          accessibilityRole="button"
          onPress={() => router.push('/friends')}
        >
          <View style={styles.friendsHeading}>
            <Users size={19} color={COLORS.primary} strokeWidth={2} />
            <Text style={styles.friendsTitle}>친구와 함께</Text>
            <ChevronRight size={17} color={COLORS.textMuted} strokeWidth={2} />
          </View>
          {friends.length > 0 ? (
            friends.slice(0, 3).map((friend) => (
              <View key={friend.memberId} style={styles.friendPreviewRow}>
                <Text style={styles.friendName}>{friend.username}</Text>
                <Text style={[styles.friendStatus, friend.attendedToday && styles.friendDone]}>
                  {friend.attendedToday ? '오늘 운동 완료' : '오늘 기록 전'}
                </Text>
              </View>
            ))
          ) : (
            <Text style={styles.friendsEmpty}>모임을 만들거나 초대 코드로 친구와 운동을 시작해보세요.</Text>
          )}
        </TouchableOpacity>

        {/* 촬영 가이드 */}
        <View style={styles.guideBox}>
          <View style={styles.guideHeaderRow}>
            <Camera size={16} color={COLORS.text} strokeWidth={2} />
            <Text style={styles.guideHeader}>운동 촬영 가이드</Text>
          </View>
          <GuideItem Icon={Ruler} text="스쿼트는 정면 기준 45° · 런지는 측면에 카메라 고정" />
          <GuideItem Icon={PersonStanding} text="머리부터 발목까지 담고 시작·종료 자세를 2초 유지" />
          <GuideItem Icon={Lightbulb} text="밝은 조명에서 관절이 선명하게 보이도록 촬영" />
          <GuideItem Icon={Ban} text="무릎을 가리는 랙·기구와 여러 사람 환경은 피해주세요" />
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

  goalCard: {
    marginHorizontal: SPACING.xxl,
    marginTop: SPACING.md,
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
  dailyCardSync: { fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.textSecondary },

  friendsCard: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    marginHorizontal: SPACING.xxl,
    marginBottom: SPACING.xl,
    padding: SPACING.lg,
  },
  friendsHeading: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm, marginBottom: SPACING.sm },
  friendsTitle: { flex: 1, color: COLORS.text, fontSize: FONT_SIZE.md, fontWeight: '700' },
  friendsEmpty: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 20 },
  friendPreviewRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: SPACING.sm },
  friendName: { color: COLORS.text, fontSize: FONT_SIZE.sm },
  friendStatus: { color: COLORS.textMuted, fontSize: FONT_SIZE.sm },
  friendDone: { color: COLORS.primary },

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
