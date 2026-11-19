import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ChevronLeft, Pause, Play } from 'lucide-react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import MotionCompareView from '@/components/compare/MotionCompareView';
import TimelineBar, { SEGMENT_COLOR } from '@/components/compare/TimelineBar';
import type { CompareMode, CompareViewHandle, PositionEvent } from '@/components/compare/types';
import { compareRecording, type MetricSummary, type RepComparison, type ReferenceSet } from '@/lib/motion/analysis';
import { feedbackForRep, feedbackForSession, type FeedbackItem } from '@/lib/motion/feedback';
import { motionRecorder } from '@/lib/motion/recorder';
import { prepareReference } from '@/lib/motion/reference';
import { SEGMENT_LABEL, type ReferenceCycle, type SegmentId } from '@/lib/motion/types';
import { buildPayloads } from '@/lib/motion/viz';

const EXERCISE_NAME = { squat: '스쿼트', lunge: '런지' } as const;
const SEGMENTS_SHOWN: SegmentId[] = ['start', 'descend', 'bottom', 'ascend'];

function fmtMetric(m: Pick<MetricSummary, 'unit'>, v: number): string {
  if (!Number.isFinite(v)) return '-';
  return m.unit === '°' ? `${Math.round(v)}°` : `${Math.round(v * 100)}%p`;
}

function scoreColor(score: number): string {
  return score >= 75 ? COLORS.syncHigh : score >= 50 ? COLORS.syncMid : COLORS.syncLow;
}

/** 3D 운동 비교 — 기준 시범 모션과 내 동작을 DTW 로 정렬해 함께 보여 주고, 어디가 얼마나 다른지 알려준다. */
export default function CompareScreen() {
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const sessionId = Number(id);
  const recording = useMemo(() => (Number.isSafeInteger(sessionId) ? motionRecorder.get(sessionId) : null), [sessionId]);

  const view = useRef<CompareViewHandle>(null);
  const [refs, setRefs] = useState<ReferenceSet | null>(null);
  const [refError, setRefError] = useState<string | null>(null);
  const [repIdx, setRepIdx] = useState(0);
  const [mode, setMode] = useState<CompareMode>('side');
  const [playing, setPlaying] = useState(true);
  const [speed, setSpeed] = useState(1);
  const [frac, setFrac] = useState(0);
  const [segment, setSegment] = useState<SegmentId | null>(null);
  const seeking = useRef(false);

  const handleReference = useCallback((cycles: ReferenceCycle[]) => {
    try {
      const prepared = cycles.map((c) => prepareReference(c));
      if (recording?.exercise === 'squat') setRefs({ squat: prepared[0] });
      else setRefs({ lunge: Object.fromEntries(prepared.map((p) => [p.lead, p])) as ReferenceSet['lunge'] });
      setRefError(null);
    } catch (e) {
      setRefError((e as Error).message);
    }
  }, [recording?.exercise]);

  const handlePosition = useCallback((e: PositionEvent) => {
    if (!seeking.current) setFrac(e.frac);
    setSegment(e.segment);
  }, []);

  const comparison = useMemo(() => (recording && refs ? compareRecording(recording, refs) : null), [recording, refs]);
  const payloads = useMemo(
    () => (recording && refs && comparison ? buildPayloads(recording, comparison.reps, refs) : []),
    [recording, refs, comparison],
  );
  const analyzedReps = useMemo(
    () => (comparison ? comparison.reps.filter((r): r is RepComparison => r.status === 'ok') : []),
    [comparison],
  );
  const rep = analyzedReps[repIdx] ?? null;
  const payload = payloads[repIdx] ?? null;

  // 반복을 바꾸거나 데이터가 준비되면 장면에 보내고 처음부터 재생한다
  useEffect(() => {
    if (!payload) return;
    view.current?.setMotion(payload);
    view.current?.setMode(mode);
    view.current?.setSpeed(speed);
    view.current?.play(true);
    setPlaying(true);
    setFrac(0);
    // mode·speed 는 별도 핸들러가 장면에 직접 보낸다 — 여기서는 반복이 바뀔 때만 다시 보낸다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payload]);

  const togglePlay = () => { const next = !playing; setPlaying(next); view.current?.play(next); };
  const changeMode = (m: CompareMode) => { setMode(m); view.current?.setMode(m); };
  const changeSpeed = (s: number) => { setSpeed(s); view.current?.setSpeed(s); };

  const sessionFeedback: FeedbackItem[] = useMemo(() => (comparison ? feedbackForSession(comparison) : []), [comparison]);
  const repFeedback: FeedbackItem[] = useMemo(() => (rep && recording ? feedbackForRep(recording.exercise, rep) : []), [rep, recording]);
  const checks = sessionFeedback.filter((f) => f.severity === 'check');

  const back = () => { if (router.canGoBack()) router.back(); else router.replace('/(tabs)' as any); };

  if (!recording) {
    return (
      <SafeAreaView style={styles.container}>
        <Header onBack={back} />
        <View style={styles.center}>
          <Text style={styles.title}>이 운동의 동작 기록이 없어요</Text>
          <Text style={styles.muted}>
            3D 비교는 운동 중에 모은 관절 좌표로 만들어요. 앱을 완전히 종료했거나 스쿼트·런지가 아니면 볼 수 없어요. 운동을 한 번 더 하고 바로 열어주세요.
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <Header onBack={back} />

        {/* 상단: 운동 이름 · 점수 · 요약 */}
        <Text style={styles.exerciseName}>{EXERCISE_NAME[recording.exercise]} · 내 동작 3D 비교</Text>
        {comparison && comparison.similarity != null ? (
          <View style={styles.card}>
            <View style={styles.scoreRow}>
              <Text style={[styles.score, { color: scoreColor(comparison.similarity) }]}>{comparison.similarity}점</Text>
              <View style={{ flex: 1 }}>
                <Text style={styles.cardTitle}>기준 시범 대비 동작 유사도</Text>
                <Text style={styles.muted}>{comparison.analyzed}회 분석{comparison.reps.length > comparison.analyzed ? ` · ${comparison.reps.length - comparison.analyzed}회 제외` : ''}</Text>
              </View>
            </View>
            {checks.length ? checks.slice(0, 3).map((f) => (
              <Text key={f.id} style={styles.body}>• {f.text}</Text>
            )) : <Text style={styles.body}>기준 시범과 전반적으로 비슷하게 움직였어요.</Text>}
            <Text style={styles.footnote}>{comparison.scoring.formula}. {comparison.scoring.note} 기준과 다르다는 것이 곧 위험한 자세라는 뜻은 아니에요.</Text>
          </View>
        ) : (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>{comparison ? '비교할 반복이 없어요' : '기준 시범을 불러오는 중…'}</Text>
            <Text style={styles.muted}>
              {comparison
                ? '횟수가 세어진 반복에서 관절이 충분히 보이는 프레임이 있어야 비교할 수 있어요. 전신이 보이게 서서 다시 해보세요.'
                : '3D 시범 장면에서 기준 동작을 읽고 있어요.'}
            </Text>
          </View>
        )}
        {refError && <Text style={[styles.body, { color: COLORS.error }]}>기준 시범을 읽지 못했어요: {refError}</Text>}

        {/* 중앙: 3D 비교 장면 */}
        <View style={styles.sceneBox}>
          <MotionCompareView ref={view} exercise={recording.exercise} onReference={handleReference} onPosition={handlePosition} />
        </View>
        {payload && <Text style={styles.footnote}>{payload.note}</Text>}

        {/* 재생 제어 */}
        {payload && (
          <View style={styles.card}>
            <View style={styles.controlRow}>
              <TouchableOpacity accessibilityLabel={playing ? '일시정지' : '재생'} onPress={togglePlay} style={styles.playBtn}>
                {playing ? <Pause size={20} color={COLORS.primaryText} /> : <Play size={20} color={COLORS.primaryText} />}
              </TouchableOpacity>
              <View style={{ flex: 1 }}>
                <TimelineBar
                  value={frac}
                  segments={payload.segment}
                  onSeekStart={() => { seeking.current = true; setPlaying(false); view.current?.play(false); }}
                  onSeek={(f) => { setFrac(f); view.current?.seek(f); }}
                />
              </View>
            </View>
            <View style={styles.legendRow}>
              {SEGMENTS_SHOWN.map((s) => (
                <View key={s} style={styles.legendItem}>
                  <View style={[styles.legendDot, { backgroundColor: SEGMENT_COLOR[s] }]} />
                  <Text style={[styles.legendText, segment === s && { color: COLORS.text, fontWeight: '700' }]}>{SEGMENT_LABEL[s]}</Text>
                </View>
              ))}
            </View>
            <View style={styles.chipRow}>
              <Chip label="나란히" active={mode === 'side'} onPress={() => changeMode('side')} />
              <Chip label="겹쳐서" active={mode === 'overlay'} onPress={() => changeMode('overlay')} />
              <View style={{ width: SPACING.md }} />
              <Chip label="0.5×" active={speed === 0.5} onPress={() => changeSpeed(0.5)} />
              <Chip label="1×" active={speed === 1} onPress={() => changeSpeed(1)} />
            </View>
            <Text style={styles.footnote}>빨간 후광은 그 관절의 기준 대비 차이가 큰 곳이에요. 화면을 끌어 돌리고, 두 손가락으로 확대·축소할 수 있어요.</Text>
          </View>
        )}

        {/* 반복 선택 */}
        {analyzedReps.length > 0 && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>반복별 보기</Text>
            <View style={styles.chipRow}>
              {analyzedReps.map((r, i) => (
                <Chip key={r.repNumber} label={`${r.repNumber}회 · ${r.similarity}점`} active={i === repIdx} onPress={() => setRepIdx(i)} />
              ))}
            </View>
            {comparison && comparison.reps.filter((r) => r.status === 'skipped').map((r) => (
              <Text key={r.repNumber} style={styles.footnote}>{r.repNumber}회는 제외했어요 — {r.status === 'skipped' ? r.reason : ''}</Text>
            ))}
          </View>
        )}

        {/* 하단: 관절별 · 구간별 · 문장 피드백 */}
        {rep && (
          <>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>{rep.repNumber}회 · 관절별 차이</Text>
              {rep.metrics.map((m) => (
                <View key={m.key} style={styles.row}>
                  <Text style={styles.rowLabel}>{m.label}</Text>
                  <Text style={styles.rowValue}>평균 {fmtMetric(m, m.meanAbs)}</Text>
                  <Text style={styles.rowSub}>
                    {Math.abs(m.meanSigned) < (m.unit === '°' ? 3 : 0.03) ? '비슷' : m.meanSigned > 0 ? '기준보다 큼' : '기준보다 작음'}
                  </Text>
                </View>
              ))}
              <Text style={styles.footnote}>평균은 DTW 로 정렬한 프레임 쌍의 차이(절댓값)이고, 각도는 카메라 화면 평면에서 잰 값이에요.</Text>
            </View>

            <View style={styles.card}>
              <Text style={styles.cardTitle}>{rep.repNumber}회 · 동작 구간별</Text>
              <View style={[styles.row, { paddingBottom: 4 }]}>
                <Text style={[styles.rowLabel, styles.head]}>구간</Text>
                <Text style={[styles.cell, styles.head]}>걸린 시간</Text>
                <Text style={[styles.cell, styles.head]}>무릎</Text>
                <Text style={[styles.cell, styles.head]}>상체</Text>
              </View>
              {rep.segments.filter((s) => SEGMENTS_SHOWN.includes(s.id)).map((s) => {
                const kneeKey = rep.metrics.find((m) => m.key === 'knee' || m.key === 'leadKnee')?.key ?? 'knee';
                const timed = s.refSec > 0;
                return (
                  <View key={s.id} style={styles.row}>
                    <View style={[styles.legendDot, { backgroundColor: SEGMENT_COLOR[s.id] }]} />
                    <Text style={[styles.rowLabel, { marginLeft: 6 }]}>{SEGMENT_LABEL[s.id]}</Text>
                    <Text style={styles.cell}>{timed ? `${s.userSec.toFixed(1)}s / ${s.refSec.toFixed(1)}s` : s.frames ? `${s.userSec.toFixed(1)}s` : '-'}</Text>
                    <Text style={styles.cell}>{s.frames ? `${Math.round(s.meanAbs[kneeKey])}°` : '-'}</Text>
                    <Text style={styles.cell}>{s.frames ? `${Math.round(s.meanAbs.lean)}°` : '-'}</Text>
                  </View>
                );
              })}
              <Text style={styles.footnote}>걸린 시간은 «내 동작 / 시범» 이에요. 시범 속도는 기준 템포일 뿐 정답이 아니에요. 무릎·상체는 기준과의 평균 각도 차이예요.</Text>
            </View>

            <View style={styles.card}>
              <Text style={styles.cardTitle}>{rep.repNumber}회 · 피드백</Text>
              {repFeedback.map((f) => (
                <Text key={f.id} style={[styles.body, f.severity === 'info' && { color: COLORS.textSecondary }]}>
                  {f.severity === 'check' ? '• ' : '✓ '}{f.text}
                </Text>
              ))}
              <Text style={styles.footnote}>
                사용 프레임 {rep.frames}개 · 보간 {rep.interpolatedFrames}개. 3fps 로 찍은 짧은 동작이라 구간 시간은 ±0.3초 정도 흔들릴 수 있어요.
              </Text>
            </View>
          </>
        )}

        {comparison && (
          <Text style={styles.footnote}>
            좌표가 잡힌 프레임 {comparison.quality.usableFrames}/{comparison.quality.totalFrames}개
            {comparison.quality.interpolatedFrames ? ` (짧게 끊긴 ${comparison.quality.interpolatedFrames}프레임은 보간)` : ''}.
          </Text>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function Header({ onBack }: { onBack: () => void }) {
  return (
    <View style={styles.header}>
      <TouchableOpacity accessibilityLabel="뒤로 가기" onPress={onBack} style={styles.backButton}>
        <ChevronLeft size={22} color={COLORS.text} />
      </TouchableOpacity>
      <Text style={styles.title}>3D 동작 비교</Text>
    </View>
  );
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <TouchableOpacity accessibilityRole="button" onPress={onPress} style={[styles.chip, active && styles.chipActive]}>
      <Text style={[styles.chipText, active && styles.chipTextActive]}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  content: { paddingHorizontal: SPACING.xl, paddingBottom: SPACING.xxxl * 2 },
  header: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, paddingVertical: SPACING.md },
  backButton: { padding: 6 },
  title: { color: COLORS.text, fontSize: FONT_SIZE.xl, fontWeight: '700' },
  center: { flex: 1, padding: SPACING.xl, gap: SPACING.md },
  exerciseName: { color: COLORS.text, fontSize: FONT_SIZE.xxl, fontWeight: '800', marginBottom: SPACING.sm },
  card: { backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder, borderRadius: RADIUS.md, padding: SPACING.lg, marginTop: SPACING.md, gap: 6 },
  cardTitle: { color: COLORS.text, fontSize: FONT_SIZE.md, fontWeight: '700' },
  scoreRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.lg },
  score: { fontSize: 44, fontWeight: '800' },
  body: { color: COLORS.text, fontSize: FONT_SIZE.sm, lineHeight: 21 },
  muted: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 20 },
  footnote: { color: COLORS.textMuted, fontSize: FONT_SIZE.xs, lineHeight: 17, marginTop: 4 },
  sceneBox: { height: 340, marginTop: SPACING.md, backgroundColor: '#111', borderRadius: 12, overflow: 'hidden' },
  controlRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md },
  playBtn: { width: 40, height: 40, borderRadius: 20, backgroundColor: COLORS.primary, alignItems: 'center', justifyContent: 'center' },
  legendRow: { flexDirection: 'row', gap: SPACING.lg, flexWrap: 'wrap' },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  legendDot: { width: 8, height: 8, borderRadius: 4 },
  legendText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm, alignItems: 'center', marginTop: 4 },
  chip: { paddingHorizontal: SPACING.md, paddingVertical: 6, borderRadius: RADIUS.md, borderWidth: 1, borderColor: COLORS.cardBorder, backgroundColor: COLORS.surface },
  chipActive: { backgroundColor: COLORS.primary, borderColor: COLORS.primary },
  chipText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, fontWeight: '600' },
  chipTextActive: { color: COLORS.primaryText },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 5 },
  rowLabel: { flex: 1.3, color: COLORS.text, fontSize: FONT_SIZE.sm },
  rowValue: { flex: 1, color: COLORS.text, fontSize: FONT_SIZE.sm, fontWeight: '700', textAlign: 'right' },
  rowSub: { flex: 1, color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, textAlign: 'right' },
  cell: { flex: 1, color: COLORS.text, fontSize: FONT_SIZE.sm, textAlign: 'right' },
  head: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, fontWeight: '700' },
});
