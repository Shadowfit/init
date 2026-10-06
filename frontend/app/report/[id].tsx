import { View, Text, StyleSheet, ScrollView, TouchableOpacity, ActivityIndicator, Platform } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter, useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import {
  ChevronLeft,
  Download,
  AlertTriangle,
  Target,
  TrendingUp,
  Timer,
  Flame,
  Sparkles,
  Share2,
  type LucideIcon,
} from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import { Alert } from '@/utils/alert';
import Button from '@/components/ui/Button';
import ShareSessionSheet from '@/components/social/ShareSessionSheet';
import { exercisesService } from '@/services/exercisesService';
import { reportService } from '@/services/reportService';
import type { SessionFeedbackSummary } from '@/types/feedback';
import { FEEDBACK_TYPE_LABEL } from '@/types/feedback';
import type { SessionReportResponse } from '@/types/report';

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];

// "2026-10-06T11:40:33" → "2026년 10월 6일 월요일"
function formatDate(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getFullYear()}년 ${d.getMonth() + 1}월 ${d.getDate()}일 ${WEEKDAY[d.getDay()]}요일`;
}

const esc = (s: unknown) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);

// PDF 로 저장할 HTML — 화면과 같은 수치를 인쇄용 흰 배경으로
function reportHtml(r: SessionReportResponse, fb: SessionFeedbackSummary | null): string {
  const rows = r.syncRateDetails
    .map((x) => `<tr><td>${esc(x.name)}</td><td>${esc(x.setInfo)}</td><td class="n">${Math.round(x.syncRate)}%</td></tr>`)
    .join('');
  const sets = r.sets.length
    ? `<h2>세트별</h2><table><tr><th>세트</th><th>횟수</th><th>평균 싱크로율</th></tr>${r.sets
        .map((s) => `<tr><td>${s.setNo}세트</td><td>${s.reps}회</td><td class="n">${s.avgSyncRate.toFixed(1)}%</td></tr>`)
        .join('')}</table>`
    : '';
  const reps = r.repTrend.length
    ? `<h2>회차별 싱크로율</h2><table><tr><th>회차</th><th>시점</th><th>싱크로율</th></tr>${r.repTrend
        .map((p) => `<tr${p.repNumber === r.worstSection?.repNumber ? ' class="worst"' : ''}><td>${p.repNumber}회</td><td>${esc(p.timeStamp)}</td><td class="n">${p.syncRate.toFixed(1)}%</td></tr>`)
        .join('')}</table>`
    : '';
  const feedback = fb && fb.totalCount > 0
    ? `<h2>자세 교정 알림 · 총 ${fb.totalCount}회</h2><table><tr><th>종류</th><th>횟수</th><th>평균 · 최저 싱크로율</th></tr>${fb.byType
        .map((b) => `<tr><td>${esc(FEEDBACK_TYPE_LABEL[b.feedbackType] ?? b.feedbackType)}</td><td>${b.count}회</td><td class="n">${Number(b.avgSyncRate).toFixed(1)}% · ${Number(b.minSyncRate).toFixed(1)}%</td></tr>`)
        .join('')}</table>`
    : '';
  const worst = r.worstSection
    ? `<h2>Worst 구간</h2><p><b>${esc(r.worstSection.exerciseName)} — ${esc(r.worstSection.timeStamp)}</b><br>${esc(r.worstSection.reason)}</p>`
    : '';
  const ai = r.aiSafetyReport ? `<h2>AI 안전 리포트</h2><p>${esc(r.aiSafetyReport)}</p>` : '';
  return `<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:-apple-system,'Malgun Gothic','Apple SD Gothic Neo',sans-serif;color:#1b1f17;padding:28px;font-size:13px}
h1{font-size:22px;margin:0}.date{color:#5a6152;margin:4px 0 20px}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:8px}
.card{border:1px solid #dadfd1;border-radius:8px;padding:12px;text-align:center}.card b{display:block;font-size:20px}.card span{color:#5a6152;font-size:11px}
h2{font-size:15px;margin:22px 0 8px;border-bottom:2px solid #1b1f17;padding-bottom:4px}
table{width:100%;border-collapse:collapse}td,th{padding:6px 4px;border-bottom:1px solid #e3e6dc;text-align:left}th{color:#5a6152;font-size:11px}
.n{text-align:right;font-variant-numeric:tabular-nums}tr.worst td{background:#fbe9e9}
.foot{margin-top:28px;color:#8a9081;font-size:10px}
</style></head><body>
<h1>ShadowFit 운동 보고서</h1><div class="date">${esc(formatDate(r.startTime))}</div>
<div class="grid">
<div class="card"><b>${r.avgSyncRate}%</b><span>평균 싱크로율</span></div>
<div class="card"><b>${r.totalReps}</b><span>총 운동 횟수</span></div>
<div class="card"><b>${r.workoutMinutes}분</b><span>운동 시간</span></div>
<div class="card"><b>${r.caloriesBurned}</b><span>소모 칼로리</span></div>
</div>
<h2>종목별 싱크로율</h2><table><tr><th>종목</th><th>세트</th><th>싱크로율</th></tr>${rows}</table>
${sets}${reps}${feedback}${worst}${ai}
<div class="foot">세션 #${r.sessionId} · AI 서버가 분석한 rep 기록(pose_data)에서 백엔드가 집계한 값</div>
</body></html>`;
}

export default function ReportScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const sessionId = id ? Number(id) : NaN;

  // 운동 화면이 router.replace 로 들어와서 stack 이 비어있을 수 있음.
  // canGoBack false 면 탭 홈으로 안전하게 이동.
  const handleBack = () => {
    if (router.canGoBack()) {
      router.back();
    } else {
      router.replace('/(tabs)' as any);
    }
  };

  const [report, setReport] = useState<SessionReportResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // 리포트가 아직 만들어지는 중(404 를 받아 다시 묻는 중)
  const [pending, setPending] = useState(false);
  // 자세 교정 이벤트 집계 (backend SessionFeedbackController)
  const [feedbackSummary, setFeedbackSummary] = useState<SessionFeedbackSummary | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);

  useEffect(() => {
    if (!Number.isFinite(sessionId)) {
      setError('잘못된 보고서 주소예요.');
      setLoading(false);
      return;
    }
    // 수치는 전부 백엔드 GET /reports/session/{id} — AI 서버가 rep 마다 저장한 pose_data 를 백엔드가 집계한 값이다.
    //
    // 운동 화면은 «종료» 직후 여기로 온다. 그런데 리포트는 종료 → (아웃박스) AI 중지 → AI 완료 콜백 뒤에야 생기는
    // **비동기** 결과라, 첫 조회는 404(R001)가 정상이다(실측: 종료 요청 0.3초 뒤 조회 → 404, 그 1초 뒤 생성).
    // 그래서 404 는 «아직» 으로 보고 잠시 간격을 두고 다시 묻는다. 끝까지 없을 때만 «찾을 수 없어요».
    let cancelled = false;
    const RETRY_DELAY_MS = 1500;
    const MAX_ATTEMPTS = 12; // ≈ 18초
    const load = async (attempt: number) => {
      try {
        const res = await reportService.getSessionReport(sessionId);
        if (cancelled) return;
        setReport(res.data);
        setPending(false);
        setLoading(false);
      } catch (e: any) {
        if (cancelled) return;
        const status = e?.response?.status;
        if (status === 404 && attempt < MAX_ATTEMPTS) {
          setPending(true);
          setTimeout(() => load(attempt + 1), RETRY_DELAY_MS);
          return;
        }
        console.warn('[session-report] status=', status);
        setPending(false);
        setError(status === 404 ? '보고서를 찾을 수 없어요. 잠시 후 홈에서 다시 열어주세요.' : '보고서를 불러오지 못했어요.');
        setLoading(false);
      }
    };
    load(1);
    exercisesService
      .getSessionFeedbackSummary(sessionId)
      .then((res) => setFeedbackSummary(res.data))
      .catch((e) => {
        console.warn('[feedback-summary] status=', e?.response?.status);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  // 다운로드 — 폰은 PDF 파일을 만들어 공유 시트(파일 저장 · 카톡 등)로, 웹은 인쇄 창(«PDF 로 저장»)으로.
  const handleDownload = async () => {
    if (!report) return;
    setDownloading(true);
    try {
      const html = reportHtml(report, feedbackSummary);
      if (Platform.OS === 'web') {
        await Print.printAsync({ html });
      } else {
        const { uri } = await Print.printToFileAsync({ html });
        if (await Sharing.isAvailableAsync()) {
          await Sharing.shareAsync(uri, { mimeType: 'application/pdf', dialogTitle: '운동 보고서 저장', UTI: 'com.adobe.pdf' });
        } else {
          Alert.alert('저장 완료', `PDF 를 만들었어요.\n${uri}`);
        }
      }
    } catch (e: any) {
      Alert.alert('다운로드 실패', e?.message ?? '보고서를 PDF 로 만들지 못했어요.');
    } finally {
      setDownloading(false);
    }
  };

  const main = report?.syncRateDetails[0];
  const defaultCaption = report && main ? `오늘 ${main.name} ${report.totalReps}회 완료! 💪` : '';

  if (loading || !report) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.header}>
          <TouchableOpacity onPress={handleBack}>
            <ChevronLeft size={20} color={COLORS.text} strokeWidth={2} />
          </TouchableOpacity>
          <Text style={styles.title}>운동 보고서</Text>
          <View style={{ width: 18 }} />
        </View>
        {loading ? (
          <View style={{ alignItems: 'center', marginTop: SPACING.xxxl, gap: SPACING.md }}>
            <ActivityIndicator color={COLORS.primary} />
            {pending && <Text style={styles.zeroNote}>AI 가 방금 운동을 정리하고 있어요…</Text>}
          </View>
        ) : (
          <Text style={styles.errorText}>{error}</Text>
        )}
      </SafeAreaView>
    );
  }

  const worstRep = report.worstSection
    ? report.repTrend.find((r) => r.repNumber === report.worstSection?.repNumber)
    : undefined;

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false}>
        {/* 헤더 */}
        <View style={styles.header}>
          <TouchableOpacity onPress={handleBack}>
            <ChevronLeft size={20} color={COLORS.text} strokeWidth={2} />
          </TouchableOpacity>
          <View style={{ alignItems: 'center' }}>
            <Text style={styles.title}>운동 보고서</Text>
            <Text style={styles.date}>{formatDate(report.startTime)}</Text>
          </View>
          <TouchableOpacity onPress={handleDownload} disabled={downloading} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
            {downloading ? (
              <ActivityIndicator size="small" color={COLORS.primary} />
            ) : (
              <Download size={18} color={COLORS.textSecondary} strokeWidth={2} />
            )}
          </TouchableOpacity>
        </View>

        {/* 요약 카드 */}
        <View style={styles.summaryGrid}>
          <SummaryCard Icon={Target} value={`${report.avgSyncRate}%`} label="평균 싱크로율" highlight />
          <SummaryCard Icon={TrendingUp} value={String(report.totalReps)} label="총 운동 횟수" />
          <SummaryCard Icon={Timer} value={`${report.workoutMinutes}분`} label="운동 시간" />
          <SummaryCard Icon={Flame} value={String(report.caloriesBurned)} label="소모 칼로리" />
        </View>

        {report.totalReps === 0 && (
          <Text style={styles.zeroNote}>
            이번 운동에서는 측정된 반복이 없어요. 전신이 화면에 들어오게 서서 다시 시도해보세요.
          </Text>
        )}

        {/* 이전 세션 대비 */}
        {report.comparisonWithPrevious && (
          <View style={styles.compareRow}>
            <Delta label="싱크로율" value={report.comparisonWithPrevious.syncRateDiff} unit="%p" />
            <Delta label="운동 시간" value={report.comparisonWithPrevious.workoutMinutesDiff} unit="분" />
            <Delta label="칼로리" value={report.comparisonWithPrevious.caloriesDiff} unit="" />
          </View>
        )}

        {/* 공유 */}
        <View style={styles.section}>
          <Button
            title="모임 피드에 공유하기"
            variant="outline"
            icon={<Share2 size={18} color={COLORS.primary} strokeWidth={2.25} />}
            onPress={() => setShareOpen(true)}
          />
        </View>

        {/* 종목별 싱크로율 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>종목별 싱크로율</Text>
          {report.syncRateDetails.map((ex) => (
            <View key={ex.exerciseId} style={styles.exerciseRow}>
              <View style={styles.exerciseInfo}>
                <Text style={styles.exerciseName}>{ex.name}</Text>
                <Text style={styles.exerciseSets}>{ex.setInfo}</Text>
              </View>
              <Text style={[styles.exerciseSync, { color: getSyncColor(ex.syncRate) }]}>
                {Math.round(ex.syncRate)}%
              </Text>
            </View>
          ))}
          {report.sets.map((s) => (
            <View key={`set-${s.setNo}`} style={styles.barRow}>
              <Text style={styles.barLabel}>{s.setNo}세트 · {s.reps}회</Text>
              <View style={styles.barBg}>
                <View style={[styles.barFill, { width: `${Math.min(100, s.avgSyncRate)}%`, backgroundColor: getSyncColor(s.avgSyncRate) }]} />
              </View>
              <Text style={[styles.barValue, { color: getSyncColor(s.avgSyncRate) }]}>{Math.round(s.avgSyncRate)}%</Text>
            </View>
          ))}
        </View>

        {/* 회차별 추이 */}
        {report.repTrend.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>회차별 싱크로율</Text>
            <View style={styles.trend}>
              {report.repTrend.map((p) => {
                const worst = p.repNumber === worstRep?.repNumber;
                return (
                  <View key={p.repNumber} style={styles.trendCol}>
                    <View style={styles.trendTrack}>
                      <View
                        style={[
                          styles.trendBar,
                          { height: `${Math.max(4, Math.min(100, p.syncRate))}%`, backgroundColor: worst ? COLORS.error : getSyncColor(p.syncRate) },
                        ]}
                      />
                    </View>
                    <Text style={[styles.trendLabel, worst && { color: COLORS.error }]}>{p.repNumber}</Text>
                  </View>
                );
              })}
            </View>
          </View>
        )}

        {/* 자세 교정 집계 - 백엔드 SessionFeedbackSummary */}
        {feedbackSummary && feedbackSummary.totalCount > 0 && (
          <View style={styles.section}>
            <View style={styles.sectionTitleRow}>
              <AlertTriangle size={18} color={COLORS.warning} strokeWidth={2} />
              <Text style={styles.sectionTitle}>
                자세 교정 알림 · 총 {feedbackSummary.totalCount}회
              </Text>
            </View>
            {feedbackSummary.byType.map((bucket) => (
              <View key={bucket.feedbackType} style={styles.feedbackBucket}>
                <View style={styles.feedbackBucketHeader}>
                  <Text style={styles.feedbackBucketLabel}>
                    {FEEDBACK_TYPE_LABEL[bucket.feedbackType] ?? bucket.feedbackType}
                  </Text>
                  <Text style={styles.feedbackBucketCount}>{bucket.count}회</Text>
                </View>
                <Text style={styles.feedbackBucketStat}>
                  평균 싱크로율 {Number(bucket.avgSyncRate).toFixed(1)}%
                  {'  '}·{'  '}최저 {Number(bucket.minSyncRate).toFixed(1)}%
                </Text>
              </View>
            ))}
          </View>
        )}

        {/* Worst 구간 */}
        {report.worstSection && (
          <View style={styles.section}>
            <View style={styles.sectionTitleRow}>
              <AlertTriangle size={18} color={COLORS.warning} strokeWidth={2} />
              <Text style={styles.sectionTitle}>Worst 구간</Text>
            </View>
            <View style={styles.worstCard}>
              <View style={styles.worstHeader}>
                <AlertTriangle size={16} color={COLORS.warning} strokeWidth={2} />
                <Text style={styles.worstTitle}>
                  {report.worstSection.exerciseName} — {report.worstSection.timeStamp}
                </Text>
              </View>
              <Text style={styles.worstDesc}>{report.worstSection.reason}</Text>
            </View>
          </View>
        )}

        {/* AI 안전 리포트 */}
        {!!report.aiSafetyReport && (
          <View style={styles.section}>
            <View style={styles.sectionTitleRow}>
              <Sparkles size={18} color={COLORS.primary} strokeWidth={2} />
              <Text style={styles.sectionTitle}>AI 안전 리포트</Text>
            </View>
            <View style={styles.aiCard}>
              <Text style={styles.aiText}>{report.aiSafetyReport}</Text>
            </View>
          </View>
        )}

        <View style={{ height: 40 }} />
      </ScrollView>

      <ShareSessionSheet
        visible={shareOpen}
        sessionId={report.sessionId}
        defaultCaption={defaultCaption}
        onClose={() => setShareOpen(false)}
      />
    </SafeAreaView>
  );
}

function SummaryCard({ Icon, value, label, highlight }: {
  Icon: LucideIcon;
  value: string;
  label: string;
  highlight?: boolean;
}) {
  return (
    <View style={[styles.summaryCard, highlight && styles.summaryCardPrimary]}>
      <Icon
        size={20}
        color={highlight ? COLORS.primary : COLORS.textSecondary}
        strokeWidth={2}
      />
      <Text style={styles.summaryValue}>{value}</Text>
      <Text style={styles.summaryLabel}>{label}</Text>
    </View>
  );
}

function Delta({ label, value, unit }: { label: string; value: number; unit: string }) {
  const color = value > 0 ? COLORS.primary : value < 0 ? COLORS.error : COLORS.textSecondary;
  return (
    <View style={styles.delta}>
      <Text style={styles.deltaLabel}>지난번 대비 {label}</Text>
      <Text style={[styles.deltaValue, { color }]}>{value > 0 ? '+' : ''}{value}{unit}</Text>
    </View>
  );
}

// 싱크로율 색상 구간: 80%+ 정석 / 60~80% 교정 필요 / <60% 부상 위험
function getSyncColor(rate: number) {
  if (rate >= 80) return COLORS.primary;
  if (rate >= 60) return COLORS.warning;
  return COLORS.error;
}

const styles = StyleSheet.create({
  errorText: { color: COLORS.textSecondary, textAlign: 'center', marginTop: SPACING.xxxl, fontSize: FONT_SIZE.md },
  zeroNote: { color: COLORS.textMuted, fontSize: FONT_SIZE.sm, paddingHorizontal: SPACING.xxl, marginTop: SPACING.md, lineHeight: 20 },
  compareRow: { flexDirection: 'row', gap: SPACING.sm, paddingHorizontal: SPACING.xxl, marginTop: SPACING.md },
  delta: { flex: 1, backgroundColor: COLORS.card, borderRadius: RADIUS.md, borderWidth: 1, borderColor: COLORS.cardBorder, padding: SPACING.md, alignItems: 'center' },
  deltaLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary },
  deltaValue: { fontSize: FONT_SIZE.lg, fontWeight: '800', marginTop: 2 },
  trend: { flexDirection: 'row', alignItems: 'flex-end', gap: 4, height: 120, backgroundColor: COLORS.card, borderRadius: RADIUS.md, borderWidth: 1, borderColor: COLORS.cardBorder, padding: SPACING.md },
  trendCol: { flex: 1, alignItems: 'center', height: '100%', maxWidth: 28 },
  trendTrack: { flex: 1, width: '100%', justifyContent: 'flex-end' },
  trendBar: { width: '100%', borderRadius: 3 },
  trendLabel: { fontSize: 10, color: COLORS.textMuted, marginTop: 4 },
  container: { flex: 1, backgroundColor: COLORS.background },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
    paddingVertical: SPACING.md,
  },
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  date: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  // Summary
  summaryGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: SPACING.md,
    paddingHorizontal: SPACING.xxl,
    marginTop: SPACING.md,
  },
  summaryCard: {
    width: '47%',
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    alignItems: 'center',
  },
  summaryCardPrimary: { borderColor: COLORS.primary },
  summaryValue: { fontSize: FONT_SIZE.xxl, fontWeight: '800', color: COLORS.text, marginTop: 4 },
  summaryLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 4 },

  // Section
  section: { paddingHorizontal: SPACING.xxl, marginTop: SPACING.xxl },
  sectionTitle: { fontSize: FONT_SIZE.lg, fontWeight: '700', color: COLORS.text, marginBottom: SPACING.md },
  sectionTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
  },

  // Exercise rows
  exerciseRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.sm,
  },
  exerciseInfo: { flex: 1 },
  exerciseName: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  exerciseSets: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, marginTop: 2 },
  exerciseSync: { fontSize: FONT_SIZE.xl, fontWeight: '800' },

  // Bar gauge
  barRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: SPACING.md,
    gap: SPACING.sm,
  },
  barLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, width: 80 },
  barBg: { flex: 1, height: 8, backgroundColor: COLORS.surfaceLight, borderRadius: 4 },
  barFill: { height: 8, borderRadius: 4 },
  barValue: { fontSize: FONT_SIZE.sm, fontWeight: '700', width: 40, textAlign: 'right' },

  // Worst
  // 자세 교정 집계
  feedbackBucket: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.sm,
  },
  feedbackBucketHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  feedbackBucketLabel: {
    fontSize: FONT_SIZE.md,
    fontWeight: '700',
    color: COLORS.text,
  },
  feedbackBucketCount: {
    fontSize: FONT_SIZE.md,
    fontWeight: '800',
    color: COLORS.warning,
  },
  feedbackBucketStat: {
    fontSize: FONT_SIZE.sm,
    color: COLORS.textSecondary,
  },

  worstCard: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
  },
  worstHeader: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm, marginBottom: SPACING.sm },
  worstTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  worstDesc: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },

  // AI Report
  aiCard: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
  },
  aiText: { fontSize: FONT_SIZE.md, color: COLORS.text, lineHeight: 24 },
});
