import { View, Text, StyleSheet } from 'react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import type { SessionReportResponse } from '@/types/report';

type AxisKey = 'depth' | 'trunk' | 'step';

interface Axis {
  key: AxisKey;
  label: string;
  score: number;
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
  focus: { label: string; score: number; cue: string; provisional: boolean } | null;
  strengths: string[];
  /** 후반부로 갈수록 떨어졌을 때의 한 줄 */
  fatigue: string | null;
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

  if (report.squatDepthAnalysis) {
    axes.push({
      key: 'depth', label: '스쿼트 깊이', provisional: false,
      score: report.squatDepthAnalysis.depthScore,
      repScores: report.squatDepthAnalysis.repDepths.map((r) => r.depthScore),
      cue: '허벅지가 바닥과 비슷해질 때까지 조금 더 앉아보세요. 엉덩이를 뒤로 빼며 내려가면 깊이가 쉬워져요.',
    });
  }
  if (report.lungeDepthAnalysis) {
    axes.push({
      key: 'depth', label: '런지 깊이', provisional: false,
      score: report.lungeDepthAnalysis.depthScore,
      repScores: report.lungeDepthAnalysis.repDepths.map((r) => r.depthScore),
      cue: '앞무릎이 90도 가까이 될 때까지 몸을 수직으로 내려보세요. 뒤무릎도 바닥 쪽으로 같이 내려가요.',
    });
  }
  const trunk = report.squatTrunkLeanAnalysis ?? report.lungeTrunkLeanAnalysis;
  if (trunk) {
    axes.push({
      key: 'trunk', label: '상체 자세', provisional: isLunge,
      score: trunk.tiltScore,
      repScores: trunk.repTrunkLeans.map((r) => r.tiltScore),
      cue: '시선을 정면보다 살짝 위에 두고 가슴을 펴보세요. 상체가 숙여지면 허리 부담이 커져요.',
    });
  }
  if (report.lungeStepLengthAnalysis) {
    axes.push({
      key: 'step', label: '보폭', provisional: true,
      score: report.lungeStepLengthAnalysis.stepScore,
      repScores: report.lungeStepLengthAnalysis.repStepLengths.map((r) => r.stepScore),
      cue: '앞발을 조금 더 멀리 내딛어 보세요. 보폭이 짧으면 앞무릎에 부담이 몰려요.',
    });
  }
  return axes;
}

/** 후반부 하락이 뚜렷하면 한 줄, 아니면 null. */
function fatigueNote(axis: Axis): string | null {
  const n = axis.repScores.length;
  if (n < MIN_REPS_FOR_TREND) return null;
  const half = Math.floor(n / 2);
  const early = avg(axis.repScores.slice(0, half));
  const late = avg(axis.repScores.slice(n - half));
  if (early - late < FATIGUE_DROP) return null;
  return `${axis.label} 점수가 앞쪽 ${half}회 평균 ${Math.round(early)}점에서 뒤쪽 ${half}회 ${Math.round(late)}점으로 떨어졌어요. 힘이 빠지는 구간이니 횟수를 줄이거나 세트 사이에 쉬어 가세요.`;
}

/**
 * 세션 리포트의 축별 점수를 «지금 가장 먼저 고칠 한 가지» 로 압축한다.
 *
 * 카드마다 따로 흩어져 있는 코멘트를 읽고 사용자가 우선순위를 직접 정해야 했다 — 여기서는
 * 가장 낮은 축 하나만 처방하고, 좋은 축은 «잘한 점» 으로 묶는다. 새 임계값은 없다
 * (등급 컷오프 90/75 는 백엔드 gradeOf 와 같다).
 */
export function buildCoaching(report: SessionReportResponse): Coaching | null {
  const axes = collectAxes(report);
  if (axes.length === 0) return null;

  const strengths = axes.filter((a) => a.score >= STRONG).map((a) => `${a.label} ${a.score}점 · 안정적이에요`);
  const weakest = axes.reduce((lo, a) => (a.score < lo.score ? a : lo));

  if (weakest.score >= STRONG) {
    return {
      tone: 'good',
      headline: '오늘 자세 아주 좋았어요! 지금 감각을 그대로 유지하세요.',
      focus: null,
      strengths,
      fatigue: null,
    };
  }

  return {
    tone: 'focus',
    headline: weakest.score >= OK
      ? `전반적으로 좋아요. 다음엔 ${weakest.label}만 더 신경 써보세요.`
      : `다음 운동에서는 ${weakest.label} 하나만 먼저 고쳐보세요.`,
    focus: { label: weakest.label, score: weakest.score, cue: weakest.cue, provisional: weakest.provisional },
    strengths,
    fatigue: fatigueNote(weakest),
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
            고칠 점 · {coaching.focus.label} {coaching.focus.score}점
          </Text>
          <Text style={styles.body}>{coaching.focus.cue}</Text>
          {coaching.focus.provisional && (
            <Text style={styles.muted}>이 항목의 기준은 아직 검증 중인 값이라 참고로만 봐주세요.</Text>
          )}
        </View>
      )}

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
