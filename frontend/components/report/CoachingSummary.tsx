import { View, Text, StyleSheet } from 'react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import { syncThresholdsFrom } from '@/constants/sync';
import type { SessionReportResponse } from '@/types/report';
import { isCounted } from '@/types/report';

type AxisKey = 'sync' | 'depth' | 'trunk' | 'step';

interface Axis {
  key: AxisKey;
  label: string;
  score: number;
  /** 화면에 붙일 단위 — 축 점수는 «점», 싱크로율은 «%» */
  unit: '점' | '%';
  /** 이 이상이면 «잘한 점», 이 미만이면 고칠 후보 */
  strong: number;
  /** 이 이상이면 «전반적으로 좋아요» 쪽 문구 */
  ok: number;
  /** rep 순서대로의 점수. 후반부 하락(피로) 판정에 쓴다. */
  repScores: number[];
  /** 임계값이 실촬영 검증 전인 축 — 단정적으로 말하지 않는다. */
  provisional: boolean;
  /** 이 축이 낮을 때 «무엇을 바꾸면 되는지» */
  cue: string;
}

export interface Coaching {
  tone: 'good' | 'focus';
  headline: string;
  /** 지금 가장 먼저 고칠 한 가지. 전부 좋으면 null */
  focus: { label: string; score: number; unit: '점' | '%'; cue: string; provisional: boolean } | null;
  strengths: string[];
  /** 후반부로 갈수록 떨어졌을 때의 한 줄 */
  fatigue: string | null;
  /** 미인정 시도가 있으면 «N회는 인정되지 않았어요 …» 한 줄 */
  rejected: string | null;
}

// 점수대 기준은 백엔드 gradeOf 와 같은 컷오프(90/75)를 쓴다 — 카드의 등급 배지와 말이 어긋나지 않게.
const STRONG = 90;
const OK = 75;
// 전반부/후반부 평균이 이만큼 벌어지면 «뒤로 갈수록 무너짐» 으로 본다. 잰 값이 아니라 정한 값이다
// (rep 수가 6개 미만이면 반으로 가를 수 없어 판정하지 않는다).
const FATIGUE_DROP = 12;
const MIN_REPS_FOR_TREND = 6;

function avg(xs: number[]): number {
  return xs.reduce((a, b) => a + b, 0) / xs.length;
}

function collectAxes(report: SessionReportResponse): Axis[] {
  const axes: Axis[] = [];
  const isLunge = !!report.lungeDepthAnalysis || !!report.lungeTrunkLeanAnalysis || !!report.lungeStepLengthAnalysis;
  const axis = (a: Omit<Axis, 'unit' | 'strong' | 'ok'>): Axis => ({ ...a, unit: '점', strong: STRONG, ok: OK });

  // 싱크로율도 축으로 넣는다(2026-10-21) — 빠져 있어서 깊이·체간이 100점이면 싱크로율이 «교정 필요» 여도
  // «오늘 자세 아주 좋았어요» 가 떴다. 경계는 이 종목의 판정 기준(정석·위험, 관리자 설정)이다.
  // 싱크로율 평균은 미인정 시도까지 포함한 값이다(2026-10-21). «가장 달랐던 회차» 와 피로 추세는 인정 회차로만 본다 —
  // 미인정 시도는 아래 rejectedLine 이 따로 말한다.
  const countedTrend = report.repTrend.filter(isCounted);
  const hasSync = report.scoreAvailable !== false && report.repTrend.length > 0;
  if (hasSync) {
    const t = syncThresholdsFrom(report);
    const worst = countedTrend.length ? countedTrend.reduce((lo, r) => (r.syncRate < lo.syncRate ? r : lo)) : null;
    axes.push({
      key: 'sync', label: '싱크로율', unit: '%', strong: t.good, ok: t.warn, provisional: false,
      score: Math.round(report.avgSyncRate),
      repScores: countedTrend.map((r) => r.syncRate),
      cue: worst
        ? `시범 동작과 가장 달랐던 건 ${worst.repNumber}회차(${Math.round(worst.syncRate)}%)예요. «내 동작 3D 비교»에서 그 회차가 시범과 어디서 갈라지는지 확인해 보세요.`
        : '인정된 회차가 없어요. 충분히 앉아 바닥에서 잠깐 멈췄다 일어나 보세요.',
    });
  }

  if (report.squatDepthAnalysis) {
    const target = report.squatDepthAnalysis.targetKneeDeg;
    axes.push(axis({
      key: 'depth', label: '스쿼트 깊이', provisional: false,
      score: report.squatDepthAnalysis.depthScore,
      repScores: report.squatDepthAnalysis.repDepths.map((r) => r.depthScore),
      cue: target != null
        ? `무릎이 ${Math.round(target)}° 이하가 될 때까지(허벅지가 바닥과 평행보다 조금 더) 앉아보세요. 엉덩이를 뒤로 빼며 내려가면 깊이가 쉬워져요.`
        : '허벅지가 바닥과 비슷해질 때까지 조금 더 앉아보세요. 엉덩이를 뒤로 빼며 내려가면 깊이가 쉬워져요.',
    }));
  }
  if (report.lungeDepthAnalysis) {
    axes.push(axis({
      key: 'depth', label: '런지 깊이', provisional: false,
      score: report.lungeDepthAnalysis.depthScore,
      repScores: report.lungeDepthAnalysis.repDepths.map((r) => r.depthScore),
      cue: '앞무릎이 90도 가까이 될 때까지 몸을 수직으로 내려보세요. 뒤무릎도 바닥 쪽으로 같이 내려가요.',
    }));
  }
  const trunk = report.squatTrunkLeanAnalysis ?? report.lungeTrunkLeanAnalysis;
  if (trunk) {
    axes.push(axis({
      key: 'trunk', label: '상체 자세', provisional: isLunge,
      score: trunk.tiltScore,
      repScores: trunk.repTrunkLeans.map((r) => r.tiltScore),
      cue: '시선을 정면보다 살짝 위에 두고 가슴을 펴보세요. 상체가 숙여지면 허리 부담이 커져요.',
    }));
  }
  if (report.lungeStepLengthAnalysis) {
    axes.push(axis({
      key: 'step', label: '보폭', provisional: true,
      score: report.lungeStepLengthAnalysis.stepScore,
      repScores: report.lungeStepLengthAnalysis.repStepLengths.map((r) => r.stepScore),
      cue: '앞발을 조금 더 멀리 내딛어 보세요. 보폭이 짧으면 앞무릎에 부담이 몰려요.',
    }));
  }
  return axes;
}

/** 축의 수준 — 2 잘함(≥ strong) · 1 괜찮음(≥ ok) · 0 고칠 것. 축마다 눈금이 달라 점수 대신 이것으로 비교한다. */
function levelOf(a: Axis): number {
  return a.score >= a.strong ? 2 : a.score >= a.ok ? 1 : 0;
}

/** 후반부 하락이 뚜렷하면 한 줄, 아니면 null. */
function fatigueNote(axis: Axis): string | null {
  const n = axis.repScores.length;
  if (n < MIN_REPS_FOR_TREND) return null;
  const half = Math.floor(n / 2);
  const early = avg(axis.repScores.slice(0, half));
  const late = avg(axis.repScores.slice(n - half));
  if (early - late < FATIGUE_DROP) return null;
  return `${axis.label}${axis.unit === '%' ? '이' : ' 점수가'} 앞쪽 ${half}회 평균 ${Math.round(early)}${axis.unit}에서 뒤쪽 ${half}회 ${Math.round(late)}${axis.unit}로 떨어졌어요. 힘이 빠지는 구간이니 횟수를 줄이거나 세트 사이에 쉬어 가세요.`;
}

/**
 * 세션 리포트의 축별 점수를 «지금 가장 먼저 고칠 한 가지» 로 압축한다.
 *
 * 카드마다 따로 흩어져 있는 코멘트를 읽고 사용자가 우선순위를 직접 정해야 했다 — 여기서는
 * 가장 낮은 축 하나만 처방하고, 좋은 축은 «잘한 점» 으로 묶는다. 새 임계값은 없다
 * (축 점수 컷오프 90/75 는 백엔드 gradeOf, 싱크로율은 종목의 정석·위험 기준).
 * 축마다 눈금이 달라서 «가장 약한 축» 은 수준(잘함/괜찮음/고칠 것)으로 먼저 고르고, 같은 수준이면
 * «잘함» 경계에서 가장 많이 모자란 축을 고른다.
 */
export function buildCoaching(report: SessionReportResponse): Coaching | null {
  const axes = collectAxes(report);
  if (axes.length === 0) return null;
  const rejectedN = report.rejectedReps ?? 0;
  const target = report.squatDepthAnalysis?.targetKneeDeg;
  const rejected = rejectedN > 0
    ? `${rejectedN}회는 인정되지 않았어요(${report.rejectedRepsByReason?.SPLIT_STANCE ? '런지 자세가 섞였거나 ' : ''}바닥 자세가 확인되지 않음). `
      + `${target != null ? `무릎 ${Math.round(target)}° 이하까지 ` : '충분히 '}앉아 바닥에서 잠깐 멈췄다 일어나야 1회로 세요.`
    : null;

  const strengths = axes.filter((a) => levelOf(a) === 2).map((a) => (a.unit === '%'
    ? `${a.label} ${a.score}% · 정석 기준(${a.strong}%) 이상이에요`
    : `${a.label} ${a.score}점 · 안정적이에요`));
  const weakest = axes.reduce((lo, a) => {
    const la = levelOf(a), ll = levelOf(lo);
    if (la !== ll) return la < ll ? a : lo;
    return a.score - a.strong < lo.score - lo.strong ? a : lo;
  });

  if (levelOf(weakest) === 2) {
    // 축 점수가 전부 좋아도 미인정 시도가 있으면 «아주 좋았어요» 라고 하지 않는다 — 그 시도가 오늘 고칠 점이다
    return rejected
      ? { tone: 'focus', headline: `대부분 좋았지만 ${rejectedN}회는 1회로 인정되지 않았어요.`, focus: null, strengths, fatigue: null, rejected }
      : { tone: 'good', headline: '오늘 자세 아주 좋았어요! 지금 감각을 그대로 유지하세요.', focus: null, strengths, fatigue: null, rejected: null };
  }

  return {
    tone: 'focus',
    headline: levelOf(weakest) === 1
      ? `전반적으로 좋아요. 다음엔 ${weakest.label}만 더 신경 써보세요.`
      : `다음 운동에서는 ${weakest.label} 하나만 먼저 고쳐보세요.`,
    focus: { label: weakest.label, score: weakest.score, unit: weakest.unit, cue: weakest.cue, provisional: weakest.provisional },
    strengths,
    fatigue: fatigueNote(weakest),
    rejected,
  };
}

export default function CoachingSummary({ report }: { report: SessionReportResponse }) {
  const coaching = buildCoaching(report);
  if (!coaching) return null;
  const accent = coaching.tone === 'good' ? COLORS.success : COLORS.primary;

  return (
    <View style={[styles.card, { borderColor: accent }]}>
      <Text style={styles.kicker}>오늘의 코칭</Text>
      <Text style={styles.headline}>{coaching.headline}</Text>

      {coaching.focus && (
        <View style={styles.focusBox}>
          <Text style={[styles.focusLabel, { color: accent }]}>
            고칠 점 · {coaching.focus.label} {coaching.focus.score}{coaching.focus.unit}
          </Text>
          <Text style={styles.body}>{coaching.focus.cue}</Text>
          {coaching.focus.provisional && (
            <Text style={styles.muted}>이 항목의 기준은 아직 검증 중인 값이라 참고로만 봐주세요.</Text>
          )}
        </View>
      )}

      {coaching.rejected && <Text style={[styles.body, styles.warn]}>{coaching.rejected}</Text>}
      {coaching.fatigue && <Text style={[styles.body, styles.warn]}>{coaching.fatigue}</Text>}

      {coaching.strengths.map((line) => (
        <Text key={line} style={styles.body}>✓ {line}</Text>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: COLORS.card,
    borderWidth: 2,
    borderRadius: RADIUS.md,
    padding: SPACING.lg,
    marginTop: SPACING.md,
    gap: 6,
  },
  kicker: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, fontWeight: '700' },
  headline: { color: COLORS.text, fontSize: FONT_SIZE.lg, fontWeight: '800', lineHeight: 26 },
  focusBox: { marginTop: SPACING.sm, gap: 4 },
  focusLabel: { fontSize: FONT_SIZE.md, fontWeight: '700' },
  body: { color: COLORS.text, fontSize: FONT_SIZE.sm, lineHeight: 21 },
  warn: { color: COLORS.warning },
  muted: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 20 },
});
