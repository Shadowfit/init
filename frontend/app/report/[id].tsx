import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft, Target, Timer } from 'lucide-react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import { reportService } from '@/services/reportService';
import { exercisesService } from '@/services/exercisesService';
import type { SessionReportResponse } from '@/types/report';
import { DEPTH_GRADE_LABEL } from '@/types/report';
import type { SessionFeedbackSummary } from '@/types/feedback';
import { FEEDBACK_TYPE_LABEL } from '@/types/feedback';

export default function ReportScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const sessionId = Number(id);
  const [report, setReport] = useState<SessionReportResponse | null>(null);
  const [feedback, setFeedback] = useState<SessionFeedbackSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const load = useCallback(async () => {
    if (!Number.isSafeInteger(sessionId) || sessionId <= 0) {
      setError(true);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(false);
    try {
      const response = await reportService.getSessionReport(sessionId);
      setReport(response.data);
      const summary = await exercisesService.getSessionFeedbackSummary(sessionId).catch(() => null);
      setFeedback(summary?.data ?? null);
    } catch {
      setReport(null);
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [sessionId]);

  useEffect(() => { void load(); }, [load]);

  const handleBack = () => {
    if (router.canGoBack()) router.back();
    else router.replace('/(tabs)' as any);
  };

  const exercise = report?.syncRateDetails?.[0];
  const hasScore = report?.scoreAvailable === true;

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.header}>
          <TouchableOpacity accessibilityLabel="뒤로 가기" onPress={handleBack} style={styles.backButton}>
            <ChevronLeft size={22} color={COLORS.text} />
          </TouchableOpacity>
          <Text style={styles.title}>운동 기록</Text>
        </View>

        {loading ? (
          <View style={styles.center}><ActivityIndicator color={COLORS.primary} /><Text style={styles.muted}>기록을 불러오는 중...</Text></View>
        ) : error || !report ? (
          <View style={styles.center}>
            <Text style={styles.message}>기록을 불러오지 못했습니다.</Text>
            <TouchableOpacity accessibilityRole="button" onPress={() => void load()} style={styles.retry}>
              <Text style={styles.retryText}>다시 시도</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <>
            <Text style={styles.exerciseName}>{exercise?.name ?? '운동'}</Text>
            <Text style={styles.muted}>완료한 운동 세션의 기록입니다.</Text>

            <View style={styles.summaryRow}>
              <View style={styles.summaryCard}>
                <Target size={19} color={COLORS.primary} />
                <Text style={styles.summaryValue}>{report.totalReps}회</Text>
                <Text style={styles.muted}>완료한 횟수</Text>
              </View>
              <View style={styles.summaryCard}>
                <Timer size={19} color={COLORS.primary} />
                <Text style={styles.summaryValue}>{report.workoutMinutes}분</Text>
                <Text style={styles.muted}>운동 시간</Text>
              </View>
            </View>

            {hasScore ? (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>동작 유사도 · 참고용</Text>
                <Text style={styles.score}>{report.avgSyncRate}%</Text>
                <Text style={styles.muted}>기준 동작과 비교한 값으로, 자세 안전성이나 부상 위험을 판정하지 않습니다.</Text>
              </View>
            ) : (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>횟수 측정 기록</Text>
                <Text style={styles.muted}>이 운동에서는 동작 유사도를 측정하지 않았습니다.</Text>
              </View>
            )}

            {report.squatDepthAnalysis && (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>
                  {report.squatDepthAnalysis.depthMode === 'HALF' ? '하프 스쿼트' : '풀 스쿼트'} 깊이 정확도
                </Text>
                <View style={styles.depthScoreRow}>
                  <Text style={styles.score}>{report.squatDepthAnalysis.depthScore}점</Text>
                  <View style={styles.depthGradeBadge}>
                    <Text style={styles.depthGradeBadgeText}>
                      {DEPTH_GRADE_LABEL[report.squatDepthAnalysis.depthGrade]}
                    </Text>
                  </View>
                </View>
                {report.squatDepthAnalysis.narrative.map((line, i) => (
                  <Text key={i} style={styles.body}>{line}</Text>
                ))}
              </View>
            )}

            {report.lungeDepthAnalysis && (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>런지 깊이 정확도</Text>
                <View style={styles.depthScoreRow}>
                  <Text style={styles.score}>{report.lungeDepthAnalysis.depthScore}점</Text>
                  <View style={styles.depthGradeBadge}>
                    <Text style={styles.depthGradeBadgeText}>
                      {DEPTH_GRADE_LABEL[report.lungeDepthAnalysis.depthGrade]}
                    </Text>
                  </View>
                </View>
                {(report.lungeDepthAnalysis.leftAvgKneeAngle != null ||
                  report.lungeDepthAnalysis.rightAvgKneeAngle != null) && (
                  <Text style={styles.muted}>
                    {report.lungeDepthAnalysis.leftAvgKneeAngle != null
                      ? `왼쪽 평균 ${Math.round(report.lungeDepthAnalysis.leftAvgKneeAngle)}도`
                      : '왼쪽 기록 없음'}
                    {' · '}
                    {report.lungeDepthAnalysis.rightAvgKneeAngle != null
                      ? `오른쪽 평균 ${Math.round(report.lungeDepthAnalysis.rightAvgKneeAngle)}도`
                      : '오른쪽 기록 없음'}
                  </Text>
                )}
                {report.lungeDepthAnalysis.narrative.map((line, i) => (
                  <Text key={i} style={styles.body}>{line}</Text>
                ))}
              </View>
            )}

            {(report.squatTrunkLeanAnalysis ?? report.lungeTrunkLeanAnalysis) && (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>체간(상체) 자세 정확도</Text>
                {(() => {
                  const trunkLean = (report.squatTrunkLeanAnalysis ?? report.lungeTrunkLeanAnalysis)!;
                  return (
                    <>
                      <View style={styles.depthScoreRow}>
                        <Text style={styles.score}>{trunkLean.tiltScore}점</Text>
                        <View style={styles.depthGradeBadge}>
                          <Text style={styles.depthGradeBadgeText}>
                            {DEPTH_GRADE_LABEL[trunkLean.tiltGrade]}
                          </Text>
                        </View>
                      </View>
                      {trunkLean.narrative.map((line, i) => (
                        <Text key={i} style={styles.body}>{line}</Text>
                      ))}
                    </>
                  );
                })()}
              </View>
            )}

            {report.lungeStepLengthAnalysis && (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>보폭 정확도</Text>
                <View style={styles.depthScoreRow}>
                  <Text style={styles.score}>{report.lungeStepLengthAnalysis.stepScore}점</Text>
                  <View style={styles.depthGradeBadge}>
                    <Text style={styles.depthGradeBadgeText}>
                      {DEPTH_GRADE_LABEL[report.lungeStepLengthAnalysis.stepGrade]}
                    </Text>
                  </View>
                </View>
                {report.lungeStepLengthAnalysis.narrative.map((line, i) => (
                  <Text key={i} style={styles.body}>{line}</Text>
                ))}
              </View>
            )}

            {exercise?.setInfo ? (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>운동 내역</Text>
                <Text style={styles.body}>{exercise.name} · {exercise.setInfo}</Text>
              </View>
            ) : null}

            {feedback && feedback.totalCount > 0 ? (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>동작 참고 알림 · {feedback.totalCount}회</Text>
                {feedback.byType.map((item) => (
                  <Text key={item.feedbackType} style={styles.body}>
                    {FEEDBACK_TYPE_LABEL[item.feedbackType] ?? item.feedbackType} · {item.count}회
                  </Text>
                ))}
              </View>
            ) : null}

            {report.aiSafetyReport?.trim() ? (
              <View style={styles.card}>
                <Text style={styles.cardTitle}>운동 메모</Text>
                <Text style={styles.body}>{report.aiSafetyReport}</Text>
              </View>
            ) : null}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  content: { paddingHorizontal: SPACING.xxl, paddingBottom: SPACING.xxxl },
  header: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, paddingTop: SPACING.lg, marginBottom: SPACING.xl },
  backButton: { padding: SPACING.xs },
  title: { color: COLORS.text, fontSize: FONT_SIZE.xxl, fontWeight: '800' },
  exerciseName: { color: COLORS.text, fontSize: FONT_SIZE.xl, fontWeight: '800', marginBottom: SPACING.xs },
  summaryRow: { flexDirection: 'row', gap: SPACING.md, marginTop: SPACING.xl },
  summaryCard: { flex: 1, backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder, borderRadius: RADIUS.md, padding: SPACING.lg },
  summaryValue: { color: COLORS.text, fontSize: FONT_SIZE.xxl, fontWeight: '800', marginTop: SPACING.sm },
  card: { backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder, borderRadius: RADIUS.md, padding: SPACING.lg, marginTop: SPACING.md },
  cardTitle: { color: COLORS.text, fontSize: FONT_SIZE.md, fontWeight: '700', marginBottom: SPACING.sm },
  score: { color: COLORS.primary, fontSize: 32, fontWeight: '800', marginBottom: SPACING.sm },
  depthScoreRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, marginBottom: SPACING.sm },
  depthGradeBadge: { paddingHorizontal: SPACING.md, paddingVertical: 4, borderRadius: RADIUS.md, backgroundColor: COLORS.primary + '22' },
  depthGradeBadgeText: { color: COLORS.primary, fontWeight: '700', fontSize: FONT_SIZE.sm },
  body: { color: COLORS.text, fontSize: FONT_SIZE.sm, lineHeight: 21, marginTop: 3 },
  muted: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 20 },
  center: { alignItems: 'center', paddingTop: SPACING.xxxl, gap: SPACING.md },
  message: { color: COLORS.text, fontSize: FONT_SIZE.md },
  retry: { paddingHorizontal: SPACING.lg, paddingVertical: SPACING.sm },
  retryText: { color: COLORS.primary, fontWeight: '700' },
});
