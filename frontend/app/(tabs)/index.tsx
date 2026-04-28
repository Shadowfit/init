import { View, Text, StyleSheet, TouchableOpacity, ScrollView } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter, useFocusEffect } from 'expo-router';
import { Calendar, type DateData } from 'react-native-calendars';
import { useCallback, useState } from 'react';
import FontAwesome from '@expo/vector-icons/FontAwesome';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import { reportService } from '@/services/reportService';
import type { CalendarMainResponse, CalendarDay } from '@/types/report';

// 백엔드 records 를 react-native-calendars 의 markedDates 형식으로 변환
function buildMarkedDates(records: CalendarDay[]): Record<string, any> {
  const marked: Record<string, any> = {};
  for (const r of records) {
    if (!r.hasRecord) continue;
    // 싱크로율 90 이상이면 primary, 그 미만이면 warning 색으로 구분
    const goodSync = r.dailyAvgSyncRate != null && r.dailyAvgSyncRate >= 90;
    marked[r.date] = {
      marked: true,
      dotColor: goodSync ? COLORS.primary : COLORS.warning,
    };
  }
  return marked;
}

export default function HomeScreen() {
  const router = useRouter();
  const today = new Date().toISOString().split('T')[0];
  const [selectedDate, setSelectedDate] = useState('');

  // 캘린더가 가리키는 연/월 (사용자가 ◀▶ 눌러 바꿈)
  const initialDate = new Date();
  const [viewYear, setViewYear] = useState(initialDate.getFullYear());
  const [viewMonth, setViewMonth] = useState(initialDate.getMonth() + 1);

  const [data, setData] = useState<CalendarMainResponse | null>(null);

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

  const hasRecordOnSelected = selectedDate
    ? !!baseMarked[selectedDate]
    : !!baseMarked[today];

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={styles.scrollContent}>
        {/* 헤더 */}
        <View style={styles.header}>
          <View>
            <Text style={styles.appTitle}>ShadowFit</Text>
            <Text style={styles.appSubtitle}>AI 자세 교정 트레이너</Text>
          </View>
          <TouchableOpacity onPress={() => router.push('/(tabs)/mypage')}>
            <FontAwesome name="user-circle-o" size={28} color={COLORS.textSecondary} />
          </TouchableOpacity>
        </View>

        {/* 상단 통계 카드 - 백엔드 CalendarMainResponse 의 monthly* 사용 */}
        <View style={styles.statsRow}>
          <StatCard
            icon="🔥"
            value={`${data?.monthlyExerciseDays ?? 0}일`}
            label="이번 달"
          />
          <StatCard
            icon="🎯"
            value={`${data?.totalAvgSyncRate ?? 0}%`}
            label="평균 싱크로율"
            highlight
          />
          <StatCard
            icon="🏆"
            value={`${data?.consecutiveDays ?? 0}일`}
            label="연속 기록"
          />
        </View>

        {/* 캘린더 */}
        <View style={styles.calendarContainer}>
          <Calendar
            markedDates={markedDates}
            onDayPress={(day: DateData) => setSelectedDate(day.dateString)}
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

        {/* 선택 날짜 정보 */}
        <View style={styles.dateInfo}>
          <Text style={styles.dateInfoText}>
            {selectedDate
              ? `${new Date(selectedDate).getMonth() + 1}월 ${new Date(selectedDate).getDate()}일 ${['일', '월', '화', '수', '목', '금', '토'][new Date(selectedDate).getDay()]}요일`
              : `${new Date().getMonth() + 1}월 ${new Date().getDate()}일 ${['일', '월', '화', '수', '목', '금', '토'][new Date().getDay()]}요일`}
          </Text>
          <Text style={styles.noRecordText}>
            {hasRecordOnSelected ? '운동 기록이 있습니다' : '운동 기록이 없습니다'}
          </Text>
        </View>

        {/* 촬영 가이드 */}
        <View style={styles.guideBox}>
          <Text style={styles.guideHeader}>📌 운동 촬영 가이드</Text>
          <View style={styles.guideItem}>
            <Text style={styles.guideBullet}>📐</Text>
            <Text style={styles.guideText}>정면 또는 측면(45°)에서 촬영</Text>
          </View>
          <View style={styles.guideItem}>
            <Text style={styles.guideBullet}>🧍</Text>
            <Text style={styles.guideText}>전신이 보이도록 1.5m 이상 거리 확보</Text>
          </View>
          <View style={styles.guideItem}>
            <Text style={styles.guideBullet}>💡</Text>
            <Text style={styles.guideText}>밝은 조명, 단색 배경 권장</Text>
          </View>
          <View style={styles.guideItem}>
            <Text style={styles.guideBullet}>🚫</Text>
            <Text style={styles.guideText}>거울 반사, 여러 사람이 보이는 환경은 피해주세요</Text>
          </View>
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

function StatCard({ icon, value, label, highlight }: {
  icon: string;
  value: string;
  label: string;
  highlight?: boolean;
}) {
  return (
    <View style={[styles.statCard, highlight && styles.statCardHighlight]}>
      <Text style={styles.statIcon}>{icon}</Text>
      <Text style={[styles.statValue, highlight && styles.statValueHighlight]}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
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
  statIcon: { fontSize: 18, marginBottom: 4 },
  statValue: { fontSize: FONT_SIZE.xl, fontWeight: '800', color: COLORS.text },
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
  guideHeader: {
    fontSize: FONT_SIZE.sm,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: SPACING.xs,
  },
  guideItem: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: SPACING.sm,
  },
  guideBullet: { fontSize: 14 },
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
