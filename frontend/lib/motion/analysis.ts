import { dtwAsymmetric } from './dtw';
import {
  featuresFromLandmarks, normalizeFeatures, rawVector, scaledVector, standingBaselines, weightsOf,
} from './features';
import { mean, median } from './geometry';
import { cleanRecording, type CleanFrame } from './preprocess';
import type { PreparedReference } from './reference';
import type {
  FrameFeatures, JointName, MotionExercise, MotionRecording, SegmentId, Side,
} from './types';

/**
 * 사용자 운동 기록 ↔ 기준 모션 비교 (반복 단위).
 *
 *   기록 → 전처리(신뢰도·보간·평활화) → 반복 분리(AI 가 센 횟수 기준) → 동작 구간만 다듬기
 *        → 특징 벡터 → 다변량 DTW(가중치·밴드) → 와핑 경로로 프레임 대응 → 관절별 오차·구간별 분석
 *
 * DTW 는 «시간축을 맞추는» 알고리즘이라, 속도 차이는 DTW 거리에 넣지 않고 원본 타임스탬프로 따로 잰다.
 */

// ── 설계값 ─────────────────────────────────────────────────────────────────────
/**
 * 궤적 점수 환산: 100 × exp(−평균거리 / TAU). 평균거리 1 = «눈금 기준 30° 상당의 평균 차이».
 * 2026-10-20 실촬영 보정: τ=1 에서는 기준과 거의 같은 자세(최저점 무릎 75° vs 72°)의 정상 스쿼트가
 * 3fps 프레임 잡음만으로 평균거리 0.7 → 49점이 됐다. 궤적은 보조 지표로 내리고(아래 POSTURE_WEIGHT)
 * τ 도 1.5 로 완화했다.
 */
export const SCORE_TAU = 1.5;
/**
 * 자세 점수의 축 — «최저점에서 얼마나 다른가» 를 본다(프레임 평균이 아니라 극값). 각 축은 tol 까지 감점 없음,
 * max 에서 0점, 그 사이는 선형. 값은 피드백 문장 문턱(feedback.ts: 무릎 10°·고관절 12°·상체 8°)과 같은 눈금으로
 * 잡은 설계값이다 — 단안 2D 각도는 촬영 방향에 따라 몇 도씩 흔들리므로 그 안쪽은 «같다» 로 본다.
 *   무릎  : 기준보다 20° 얕게 앉으면 0.56, 35° 이상 다르면 0
 *   고관절: 엉덩이를 접는 정도 (스쿼트 힙힌지 / 런지 앞다리)
 *   상체  : 서 있을 때 대비 최대 숙임의 차이
 */
export const POSTURE_AXES: Record<MotionExercise, Array<{ key: 'knee' | 'hip' | 'trunk'; tol: number; max: number; weight: number }>> = {
  // 2026-10-21: 무릎 허용 8→5°, 고관절 10→8° — AI 싱크로율(ai-server dtw_calculator `_KNEE/_HIP_TOLERANCE_DEG`)과
  // 리포트 깊이 점수(백엔드 SquatDepthAnalysisCalculator, 시범 최저 무릎 + 5°)와 같은 허용 오차. 셋이 같이 움직여야 한다.
  squat: [
    { key: 'knee', tol: 5, max: 35, weight: 0.5 },
    { key: 'hip', tol: 8, max: 40, weight: 0.25 },
    { key: 'trunk', tol: 8, max: 30, weight: 0.25 },
  ],
  lunge: [
    { key: 'knee', tol: 5, max: 35, weight: 0.5 },
    { key: 'hip', tol: 8, max: 40, weight: 0.2 },
    { key: 'trunk', tol: 8, max: 30, weight: 0.3 },
  ],
};

/** 궤적 점수용 사용자 곡선 보간 간격(초) — 기준(0.1초)보다 촘촘하게. */
const TRAJ_STEP_SEC = 1 / 12;

/**
 * 궤적 점수용 사용자 곡선 — (1) 기준의 «바닥»에서 자르고 (2) 시간축으로 선형 보간한다(2026-10-21).
 *
 * (1) 기준보다 더 깊게 앉은 것은 «다르다» 가 아니다(자세 점수와 같은 원칙). 무릎·고관절 각도(0·1번)는 기준의
 *     최솟값 밑으로, 엉덩이 내려간 정도(3번)는 기준의 최댓값 위로 가면 그 값으로 본다. 상체 숙임(2번)은 깊이가
 *     아니라 자세라 자르지 않는다.
 * (2) 3fps 라 반복당 3~6장인데, DTW 거리를 «찍힌 프레임» 으로 평균하면 전이 구간이 한 장 끼고 안 끼고에 따라
 *     같은 동작이 수 점씩 달라졌다(AI 싱크로율에서 같은 깊이 반복이 3장 73점·5장 90점). 프레임 사이를 이어
 *     연속 동작으로 평균하면 찍힌 장수에 둔감해진다. 와핑 경로(관절별 오차·3D 강조)는 실제 프레임 그대로 쓴다.
 */
export function trajectoryCurve(user: number[][], t: number[], ref: number[][]): number[][] {
  if (!user.length) return user;
  const dims = user[0].length;
  const lo = Array.from({ length: dims }, (_, k) => Math.min(...ref.map((r) => r[k])));
  const hi = Array.from({ length: dims }, (_, k) => Math.max(...ref.map((r) => r[k])));
  const floored = user.map((v) => v.map((x, k) => (k === 0 || k === 1 ? Math.max(x, lo[k]) : k === 3 ? Math.min(x, hi[k]) : x)));
  const ok = t.length === floored.length && t.every((x, i) => Number.isFinite(x) && (i === 0 || x > t[i - 1]));
  const times = ok ? t : floored.map((_, i) => i / 3);
  if (floored.length < 2) return floored;
  const out: number[][] = [];
  let seg = 0;
  for (let tau = times[0]; ; tau = Math.min(tau + TRAJ_STEP_SEC, times[times.length - 1])) {
    while (seg < times.length - 2 && times[seg + 1] < tau) seg++;
    const a = times[seg], b = times[seg + 1], w = b > a ? (tau - a) / (b - a) : 0;
    out.push(floored[seg].map((x, k) => x + (floored[seg + 1][k] - x) * Math.max(0, Math.min(1, w))));
    if (tau >= times[times.length - 1]) break;
  }
  return out;
}
/** 최종 점수 = POSTURE_WEIGHT × 자세 + (1 − POSTURE_WEIGHT) × 궤적 */
export const POSTURE_WEIGHT = 0.7;

/**
 * 자세 점수 0~1 — 최저점 무릎·고관절, 상체 최대 숙임이 기준보다 **나쁜 방향으로** 얼마나 다른지로 계산.
 * 기준보다 더 깊게 앉거나(무릎·고관절 각도가 더 작음) 상체를 덜 숙인 것은 감점하지 않는다 — 기준은 과녁이 아니라
 * 넘어야 할 바닥이다. 절댓값으로 깎으면 자세가 좋은 사람이 «기준과 다르다» 고 손해를 본다(2026-10-20).
 */
export function postureScore(exercise: MotionExercise, ext: { kneeMin: Extreme; hipMin: Extreme; trunkMax: Extreme }): number {
  let total = 0;
  for (const a of POSTURE_AXES[exercise]) {
    // delta = 사용자 − 기준. 무릎·고관절은 커질수록(얕을수록), 상체는 커질수록(더 숙일수록) 나쁘다
    const d = Math.max(0, a.key === 'knee' ? ext.kneeMin.delta : a.key === 'hip' ? ext.hipMin.delta : ext.trunkMax.delta);
    const penalty = Math.max(0, Math.min(1, (d - a.tol) / (a.max - a.tol)));
    total += a.weight * (1 - (Number.isFinite(penalty) ? penalty : 1));
  }
  return total;
}
/** 반복 앞뒤의 «서 있는» 프레임을 자르는 기준 — 무릎이 시작 자세보다 이만큼(도) 굽혀지면 동작 시작으로 본다. */
const ONSET_KNEE_DEG = 10;
/** 동작 구간 안에 이만큼은 있어야 비교한다(3fps 에서 약 1.3초 — 실촬영 반복이 1.3~2초라 더 높이면 절반이 빠진다). */
export const MIN_REP_FRAMES = 4;

export type MetricKey = string;

export interface MetricDef {
  key: MetricKey;
  label: string;
  unit: '°' | '';
  /** 오차가 크면 3D 장면에서 강조할 관절 */
  joints: JointName[];
}

export function metricDefs(exercise: MotionExercise, lead: Side | null): MetricDef[] {
  if (exercise === 'squat') {
    // 좌우를 따로 재지 않는다 — 45° 촬영에서는 가까운 다리와 먼 다리의 2D 각도가 30° 넘게 달라(실촬영 확인)
    // 한쪽만 «기준과 크게 다르다» 고 말하면 측정 착시를 지적하게 된다. 양쪽 평균을 쓰고 두 관절을 같이 가리킨다.
    return [
      { key: 'knee', label: '무릎 각도', unit: '°', joints: ['lKnee', 'rKnee'] },
      { key: 'hip', label: '고관절 각도', unit: '°', joints: ['lHip', 'rHip'] },
      { key: 'lean', label: '상체 숙임', unit: '°', joints: ['lShoulder', 'rShoulder'] },
      { key: 'drop', label: '엉덩이 내려간 정도', unit: '', joints: ['lHip', 'rHip'] },
    ];
  }
  const leadLeft = lead !== 'right';
  const L = leadLeft ? 'l' : 'r', T = leadLeft ? 'r' : 'l';
  return [
    { key: 'leadKnee', label: '앞다리 무릎 각도', unit: '°', joints: [`${L}Knee` as JointName] },
    { key: 'trailKnee', label: '뒷다리 무릎 각도', unit: '°', joints: [`${T}Knee` as JointName] },
    { key: 'leadHip', label: '앞다리 고관절 각도', unit: '°', joints: [`${L}Hip` as JointName] },
    { key: 'trailHip', label: '뒷다리 고관절 각도', unit: '°', joints: [`${T}Hip` as JointName] },
    { key: 'lean', label: '상체 숙임', unit: '°', joints: ['lShoulder', 'rShoulder'] },
    { key: 'drop', label: '엉덩이 내려간 정도', unit: '', joints: ['lHip', 'rHip'] },
  ];
}

export function metricValues(f: FrameFeatures, exercise: MotionExercise, lead: Side | null): Record<MetricKey, number> {
  if (exercise === 'squat') {
    return { knee: (f.kneeL + f.kneeR) / 2, hip: (f.hipL + f.hipR) / 2, lean: f.lean, drop: f.drop };
  }
  const leadLeft = lead !== 'right';
  return {
    leadKnee: leadLeft ? f.kneeL : f.kneeR, trailKnee: leadLeft ? f.kneeR : f.kneeL,
    leadHip: leadLeft ? f.hipL : f.hipR, trailHip: leadLeft ? f.hipR : f.hipL,
    lean: f.lean, drop: f.drop,
  };
}

export interface MetricSummary extends MetricDef {
  /** 정렬된 프레임 쌍의 |사용자 − 기준| 평균 */
  meanAbs: number;
  /** 사용자 − 기준 의 평균(부호 있음) */
  meanSigned: number;
}

export interface SegmentSummary {
  id: SegmentId;
  frames: number;
  /** 사용자가 이 구간에서 쓴 시간(원본 타임스탬프) */
  userSec: number;
  /** 시범이 이 구간에서 쓰는 시간 */
  refSec: number;
  /** 사용자 구간 안 |오차| 평균 — 지표별 */
  meanAbs: Record<MetricKey, number>;
  /** 사용자 − 기준 평균(부호 있음) — 지표별 */
  meanSigned: Record<MetricKey, number>;
}

export interface Extreme { user: number; ref: number; delta: number }

export interface RepComparison {
  status: 'ok';
  repNumber: number;
  lead: Side | null;
  /** 다듬은 뒤 쓴 프레임 수 */
  frames: number;
  /** 0~100. 점수 = 100 × (POSTURE_WEIGHT × 자세 + (1−POSTURE_WEIGHT) × 궤적) */
  similarity: number;
  /** 0~100. 최저점 자세(무릎·고관절·상체)의 기준 대비 차이 — postureScore() */
  postureScore: number;
  /** 0~100. 100 × exp(−meanDistance / SCORE_TAU) — DTW 로 정렬한 궤적의 평균 거리 */
  trajectoryScore: number;
  meanDistance: number;
  /** 사용자 프레임(다듬은 목록 기준) ↔ 기준 프레임 와핑 경로 */
  path: Array<[number, number]>;
  /** 쓴 프레임이 원본 기록(cleanRecording 결과)에서 차지하는 인덱스 */
  frameIndex: number[];
  userT: number[];
  /** 사용자 프레임별 대응한 기준 시간(초, 장면 시계) */
  refTime: number[];
  segment: SegmentId[];
  /** 프레임별 부호 있는 오차(사용자 − 기준), 지표별 */
  frameError: Array<Record<MetricKey, number>>;
  metrics: MetricSummary[];
  segments: SegmentSummary[];
  /** 최저점 무릎(굽힘이 가장 큰 순간)·상체 최대 숙임(서 있을 때 대비)·고관절 최저 각도 */
  kneeMin: Extreme;
  trunkMax: Extreme;
  hipMin: Extreme;
  timing: { userSec: number; refSec: number };
  /** 사용자 프레임 간격의 중앙값(초) — 구간 시간이 프레임 몇 개 분량인지 가늠하는 데 쓴다 */
  frameDt: number;
  interpolatedFrames: number;
}

export interface SkippedRep { status: 'skipped'; repNumber: number; reason: string }

export interface SessionComparison {
  exercise: MotionExercise;
  reps: Array<RepComparison | SkippedRep>;
  analyzed: number;
  /** 분석된 반복의 평균 점수. 하나도 없으면 null */
  similarity: number | null;
  quality: { totalFrames: number; usableFrames: number; interpolatedFrames: number };
  scoring: { formula: string; tau: number; note: string };
}

export interface ReferenceSet {
  squat?: PreparedReference;
  lunge?: Partial<Record<Side, PreparedReference>>;
}

interface RepWindow { repNumber: number; start: number; end: number }

/** AI 가 센 횟수가 올라간 프레임을 반복의 끝으로 본다. 반복 k 는 (k−1 번째 끝, k 번째 끝]. */
export function splitReps(frames: CleanFrame[]): RepWindow[] {
  const wins: RepWindow[] = [];
  let prevEnd = -1, counted = 0;
  frames.forEach((f, i) => {
    while (f.reps > counted) {
      counted++;
      wins.push({ repNumber: counted, start: prevEnd + 1, end: i });
      prevEnd = i;
    }
  });
  return wins;
}

/** 두 다리의 고관절 각도가 이 정도(도)는 달라야 앞/뒤다리를 구분한다(정면에 가까운 촬영은 구분이 안 된다). */
export const FRONT_LEG_MIN_HIP_GAP_DEG = 15;

/**
 * 런지의 «앞다리» — 가장 깊이 굽힌 프레임에서 고관절이 더 접힌(어깨-고관절-무릎 각도가 작은) 쪽.
 *
 * AI 서버가 알려주는 lead_leg 를 쓰지 않는 이유(2026-10-20 실촬영 확인): lunge_analyzer 는 «발목이 골반 중심에서
 * 가로로 더 먼 쪽» 을 lead 로 고르는데, 바닥 자세에서는 골반이 앞발 쪽으로 쏠려 오히려 **뒷다리**가 더 멀다.
 * 실촬영 3개(lunge_normal_01·sallow_01·walking_01)의 25개 반복에서 AI lead 는 25번 모두 고관절이 덜 접힌
 * (=뒷다리) 쪽이었다.
 */
export function frontLeg(feats: FrameFeatures[]): Side | null {
  if (!feats.length) return null;
  let best = feats[0], bestKnee = (best.kneeL + best.kneeR) / 2;
  for (const f of feats) {
    const k = (f.kneeL + f.kneeR) / 2;
    if (k < bestKnee) { best = f; bestKnee = k; }
  }
  if (Math.abs(best.hipL - best.hipR) < FRONT_LEG_MIN_HIP_GAP_DEG) return null;
  return best.hipL < best.hipR ? 'left' : 'right';
}

/** 동작 곡선(무릎 각도)에서 읽은 단계 경계 시각(초). */
export interface PhaseTimes { onset: number; bottomStart: number; bottomEnd: number; ret: number }

/** 이 각도 폭(도)보다 작게 움직인 기록은 «굽혔다 폈다» 로 보지 않는다(경계가 의미 없음). */
const MIN_PHASE_AMPLITUDE_DEG = 15;
/** 시작·최저점·끝 자세에서 진폭의 이만큼 안쪽에 들어오면 «도착» 으로 본다. */
const PHASE_BAND = 0.1;

/**
 * 무릎 각도 곡선으로 단계 경계를 나눈다 — 사용자와 기준에 **같은 규칙**을 쓴다.
 *   시작 → (onset) 내려가기 → (bottomStart) 최저점 → (bottomEnd) 올라오기 → (ret) 마무리
 * DTW 대응에 기대지 않는 이유: 내려가기 초반은 거의 안 움직여서 DTW 가 서 있는 구간에 붙여 버리고,
 * 그러면 구간 시간이 DTW 의 선택에 따라 흔들린다. 곡선에서 직접 읽으면 두 동작에 같은 잣대가 된다.
 * 경계 시각은 프레임 사이를 선형 보간한다(3fps 에서 한 프레임 = 0.33초라 보간이 없으면 너무 거칠다).
 */
export function phaseBoundaries(t: number[], knee: number[]): PhaseTimes | null {
  const n = knee.length;
  if (n < 4) return null;
  const k0 = median(knee.slice(0, Math.min(2, n)));
  const kEnd = median(knee.slice(Math.max(0, n - 2)));
  let iMin = 0;
  for (let i = 1; i < n; i++) if (knee[i] < knee[iMin]) iMin = i;
  const kMin = knee[iMin];
  const ampStart = k0 - kMin, ampEnd = kEnd - kMin;
  if (Math.min(ampStart, ampEnd) < MIN_PHASE_AMPLITUDE_DEG) return null;
  const onsetThr = k0 - PHASE_BAND * ampStart;
  const bandThr = kMin + PHASE_BAND * Math.min(ampStart, ampEnd);
  const retThr = kEnd - PHASE_BAND * ampEnd;
  const cross = (i: number, j: number, thr: number) => {
    // i→j 사이에서 knee 가 thr 를 지나는 시각
    const a = knee[i], b = knee[j];
    const u = a === b ? 0 : (thr - a) / (b - a);
    return t[i] + (t[j] - t[i]) * Math.max(0, Math.min(1, u));
  };
  // 최저점 쪽에서 거꾸로 거슬러 올라가며 경계를 찾는다
  let j = iMin;
  while (j > 0 && knee[j - 1] <= bandThr) j--;
  const bottomStart = j > 0 ? cross(j - 1, j, bandThr) : t[0];
  let o = iMin;
  while (o > 0 && knee[o - 1] < onsetThr) o--;
  const onset = o > 0 ? cross(o - 1, o, onsetThr) : t[0];
  let e = iMin;
  while (e < n - 1 && knee[e + 1] <= bandThr) e++;
  const bottomEnd = e < n - 1 ? cross(e, e + 1, bandThr) : t[n - 1];
  let r = e;
  while (r < n - 1 && knee[r + 1] < retThr) r++;
  const ret = r < n - 1 ? cross(r, r + 1, retThr) : t[n - 1];
  return { onset, bottomStart, bottomEnd, ret };
}

export function segmentAtTime(p: PhaseTimes, tau: number): SegmentId {
  if (tau < p.onset) return 'start';
  if (tau < p.bottomStart) return 'descend';
  if (tau <= p.bottomEnd) return 'bottom';
  if (tau <= p.ret) return 'ascend';
  return 'end';
}

function phaseDurations(p: PhaseTimes): Record<'descend' | 'bottom' | 'ascend', number> {
  return { descend: p.bottomStart - p.onset, bottom: p.bottomEnd - p.bottomStart, ascend: p.ret - p.bottomEnd };
}

/** 반복 앞뒤의 서 있는 프레임을 자른다(무릎이 시작·끝 자세에서 ONSET_KNEE_DEG 이상 굽혀진 구간만 남김). */
function trimToMotion(knee: number[]): [number, number] {
  const n = knee.length;
  if (n < 3) return [0, n - 1];
  const head = median(knee.slice(0, Math.min(3, n)));
  const tail = median(knee.slice(Math.max(0, n - 3)));
  let first = knee.findIndex((k) => k < head - ONSET_KNEE_DEG);
  let last = -1;
  for (let i = n - 1; i >= 0; i--) if (knee[i] < tail - ONSET_KNEE_DEG) { last = i; break; }
  if (first < 0 || last < 0 || last <= first) return [0, n - 1];
  return [Math.max(0, first - 1), Math.min(n - 1, last + 1)];
}

/** 포물선 보간으로 극값을 넘어 추정할 수 있는 최대 폭(도). 3fps 에서 한 프레임 사이 무릎은 30° 넘게 움직이므로 이 정도는 실제로 생긴다. */
const EXTREME_REFINE_MAX_DEG = 25;

/**
 * 표본 사이에 떨어진 극값 추정 — 극값 프레임과 양옆 이웃 세 점을 지나는 포물선의 꼭짓점.
 * 3fps 로 찍으면 가장 깊은 순간이 두 프레임 사이에 있기 일쑤라, 표본 최솟값만 쓰면 깊이를 늘 얕게 잰다
 * («자세는 좋은데 점수가 안 나온다» 의 한 원인). 꼭짓점이 이웃 구간 밖이거나 표본값보다 덜 극단이면 표본값을 쓴다.
 */
export function refineExtreme(t: number[], v: number[], min: boolean): number {
  const n = v.length;
  if (!n) return NaN;
  let i = 0;
  for (let k = 1; k < n; k++) if (min ? v[k] < v[i] : v[k] > v[i]) i = k;
  if (i === 0 || i === n - 1) return v[i];
  const [x0, x1, x2] = [t[i - 1] - t[i], 0, t[i + 1] - t[i]];
  const [y0, y1, y2] = [v[i - 1], v[i], v[i + 1]];
  // 세 점을 지나는 포물선 y = a·x² + b·x + c (x 는 극값 프레임 기준 상대 시각)
  const denom = (x0 - x1) * (x0 - x2) * (x1 - x2);
  if (Math.abs(denom) < 1e-9) return v[i];
  const a = (x2 * (y1 - y0) + x1 * (y0 - y2) + x0 * (y2 - y1)) / denom;
  const b = (x2 * x2 * (y0 - y1) + x1 * x1 * (y2 - y0) + x0 * x0 * (y1 - y2)) / denom;
  if ((min && a <= 0) || (!min && a >= 0)) return v[i];   // 볼록한 방향이 아니면 표본값
  const xv = -b / (2 * a);
  if (xv < x0 || xv > x2) return v[i];
  const yv = a * xv * xv + b * xv + y1;
  const bounded = min ? Math.max(yv, y1 - EXTREME_REFINE_MAX_DEG) : Math.min(yv, y1 + EXTREME_REFINE_MAX_DEG);
  return min ? Math.min(y1, bounded) : Math.max(y1, bounded);
}

/** 프레임 하나가 «맡는» 시간 — 안쪽은 앞뒤 간격의 절반씩, 양 끝은 이웃과의 간격 하나. */
function frameWeights(t: number[]): number[] {
  const n = t.length;
  if (n < 2) return [0];
  return t.map((_, i) => {
    if (i === 0) return t[1] - t[0];
    if (i === n - 1) return t[n - 1] - t[n - 2];
    return (t[i + 1] - t[i - 1]) / 2;
  });
}

function summarize(
  exercise: MotionExercise, lead: Side | null,
  refFull: PreparedReference, ref: PreparedReference,
  feats: FrameFeatures[], path: Array<[number, number]>,
): Pick<RepComparison, 'refTime' | 'segment' | 'frameError' | 'metrics' | 'segments' | 'kneeMin' | 'trunkMax' | 'hipMin' | 'timing'> {
  const defs = metricDefs(exercise, lead);
  const n = feats.length;
  // 사용자 프레임 i 가 대응한 기준 프레임들 중 가운데 것
  const matched: number[][] = Array.from({ length: n }, () => []);
  for (const [i, j] of path) matched[i].push(j);
  const refIdx = matched.map((js) => js[Math.floor(js.length / 2)]);
  const refTime = refIdx.map((j) => ref.t[j]);

  const refVals = ref.feats.map((f) => metricValues(f, ref.exercise, lead));
  const userVals = feats.map((f) => metricValues(f, exercise, lead));
  const frameError = userVals.map((u, i) => {
    const r = refVals[refIdx[i]];
    const e: Record<MetricKey, number> = {};
    for (const d of defs) e[d.key] = u[d.key] - r[d.key];
    return e;
  });

  const metrics: MetricSummary[] = defs.map((d) => ({
    ...d,
    meanAbs: mean(frameError.map((e) => Math.abs(e[d.key]))),
    meanSigned: mean(frameError.map((e) => e[d.key])),
  }));

  const kneeOf = (v: Record<MetricKey, number>) => (exercise === 'squat' ? v.knee : v.leadKnee);
  const hipOf = (v: Record<MetricKey, number>) => (exercise === 'squat' ? v.hip : v.leadHip);

  // 단계: 양쪽 모두 «무릎 각도 곡선» 에서 같은 규칙으로 읽는다. 곡선이 너무 평평하면 DTW 대응 단계로 대신한다.
  const userT = feats.map((f) => f.t);
  const userPhases = phaseBoundaries(userT, userVals.map(kneeOf));
  const refFullVals = refFull.feats.map((f) => metricValues(f, refFull.exercise, lead));
  const refPhases = phaseBoundaries(refFull.t, refFullVals.map(kneeOf));
  const segment: SegmentId[] = userPhases
    ? userT.map((tau) => segmentAtTime(userPhases, tau))
    : refIdx.map((j) => ref.segments[j]);

  const w = frameWeights(userT);
  const ids: SegmentId[] = ['start', 'descend', 'bottom', 'ascend', 'end'];
  const userDur = userPhases ? phaseDurations(userPhases) : null;
  const refDur = refPhases ? phaseDurations(refPhases) : null;
  const segments: SegmentSummary[] = ids.map((id) => {
    const idx = segment.map((x, i) => (x === id ? i : -1)).filter((i) => i >= 0);
    const meanAbs: Record<MetricKey, number> = {}, meanSigned: Record<MetricKey, number> = {};
    for (const d of defs) {
      meanAbs[d.key] = idx.length ? mean(idx.map((i) => Math.abs(frameError[i][d.key]))) : NaN;
      meanSigned[d.key] = idx.length ? mean(idx.map((i) => frameError[i][d.key])) : NaN;
    }
    const timed = id === 'descend' || id === 'bottom' || id === 'ascend';
    return {
      id, frames: idx.length,
      // 경계를 읽을 수 있으면 곡선에서 잰 시간, 아니면 프레임이 맡은 시간의 합
      userSec: timed && userDur ? userDur[id] : idx.reduce((sum, i) => sum + w[i], 0),
      refSec: timed && refDur ? refDur[id] : 0,
      meanAbs, meanSigned,
    };
  });

  const mk = (pickU: (v: Record<MetricKey, number>) => number, pickR: (v: Record<MetricKey, number>) => number, min: boolean): Extreme => {
    const f = min ? Math.min : Math.max;
    // 사용자는 3fps 라 진짜 극값이 프레임 사이에 떨어진다 — 이웃 3점 포물선으로 보간해 추정(refineExtreme). 기준은 0.1초 간격이라 그대로.
    const user = refineExtreme(userT, userVals.map(pickU), min), r = f(...refFullVals.map(pickR));
    return { user, ref: r, delta: user - r };
  };
  return {
    refTime, segment, frameError, metrics, segments,
    kneeMin: mk(kneeOf, kneeOf, true),
    trunkMax: mk((v) => v.lean, (v) => v.lean, false),
    hipMin: mk(hipOf, hipOf, true),
    timing: { userSec: userPhases ? userPhases.ret - userPhases.onset : w.reduce((a, b) => a + b, 0),
      refSec: refPhases ? refPhases.ret - refPhases.onset : refFull.durationSec },
  };
}

export function compareRecording(rec: MotionRecording, refs: ReferenceSet): SessionComparison {
  const exercise = rec.exercise;
  const clean = cleanRecording(rec);
  const wins = splitReps(clean);
  const results: Array<RepComparison | SkippedRep> = [];

  // 서 있을 때 엉덩이 높이(기준선)는 기록 전체에서 한 번 구한다 — 사람의 다리/몸통 비율을 이 값으로 나눠 없앤다
  const rawAll = clean.map((f) => (f.ok && f.lm ? featuresFromLandmarks(f.lm, rec.aspect, f.t) : null));
  const baselines = standingBaselines(rawAll.filter((f): f is FrameFeatures => !!f));
  const featAll = rawAll.map((f) => (f ? normalizeFeatures(f, baselines) : null));

  for (const win of wins) {
    // 이 반복의 쓸 수 있는 프레임 → 특징
    const idxAll: number[] = [], featsAll: FrameFeatures[] = [];
    for (let i = win.start; i <= win.end; i++) {
      const feat = featAll[i];
      if (feat && Number.isFinite(feat.drop) && Number.isFinite(feat.lean)) { idxAll.push(i); featsAll.push(feat); }
    }
    const lead = exercise === 'lunge' ? frontLeg(featsAll) : null;
    const ref = exercise === 'squat' ? refs.squat : (lead ? refs.lunge?.[lead] : undefined);
    if (!ref) {
      results.push({ status: 'skipped', repNumber: win.repNumber,
        reason: exercise === 'lunge' ? '앞다리를 구분하지 못했습니다(옆에서 촬영하면 구분됩니다)' : '기준 모션을 불러오지 못했습니다' });
      continue;
    }
    if (featsAll.length < MIN_REP_FRAMES) {
      results.push({ status: 'skipped', repNumber: win.repNumber, reason: `관절이 보이는 프레임이 부족합니다(${featsAll.length}개)` });
      continue;
    }
    const kneeMean = featsAll.map((f) => (f.kneeL + f.kneeR) / 2);
    const [a, b] = trimToMotion(kneeMean);
    const feats = featsAll.slice(a, b + 1), frameIndex = idxAll.slice(a, b + 1);
    if (feats.length < MIN_REP_FRAMES) {
      results.push({ status: 'skipped', repNumber: win.repNumber, reason: `동작 구간의 프레임이 부족합니다(${feats.length}개)` });
      continue;
    }

    const raw = feats.map((f) => rawVector(f, exercise, lead));
    const user = raw.map((r) => scaledVector(r, exercise));
    const gaps = feats.slice(1).map((f, i) => f.t - feats[i].t).filter((g) => g > 0);
    const res = dtwAsymmetric(user, ref.scaled, { weights: weightsOf(exercise) });
    const s = summarize(exercise, lead, ref, ref, feats, res.path);
    // 점수: 최저점 자세가 주(70%), DTW 궤적 거리가 보조(30%). 프레임 평균 거리만 쓰면 3fps 잡음이 자세 차이를 덮어서
    // «기준과 같은 자세가 49점, 20° 얕은 자세가 44점» 처럼 구분이 안 됐다(2026-10-20 실촬영).
    // 궤적 거리는 바닥에서 자르고 보간한 곡선으로 따로 잰다(trajectoryCurve) — 찍힌 장수·더 깊은 자세에 흔들리지 않게.
    const trajRes = dtwAsymmetric(trajectoryCurve(user, feats.map((f) => f.t), ref.scaled), ref.scaled, { weights: weightsOf(exercise) });
    const trajectory = Math.max(0, Math.min(1, Math.exp(-trajRes.meanDistance / SCORE_TAU)));
    const posture = postureScore(exercise, s);
    const similarity = Math.round(100 * (POSTURE_WEIGHT * posture + (1 - POSTURE_WEIGHT) * trajectory));
    results.push({
      status: 'ok', repNumber: win.repNumber, lead, frames: feats.length,
      similarity, postureScore: Math.round(100 * posture), trajectoryScore: Math.round(100 * trajectory),
      meanDistance: res.meanDistance, path: res.path,
      frameIndex, userT: feats.map((f) => f.t), frameDt: gaps.length ? median(gaps) : 0,
      interpolatedFrames: frameIndex.filter((i) => clean[i].interpolated).length,
      ...s,
    });
  }

  const ok = results.filter((r): r is RepComparison => r.status === 'ok');
  return {
    exercise, reps: results, analyzed: ok.length,
    similarity: ok.length ? Math.round(mean(ok.map((r) => r.similarity))) : null,
    quality: {
      totalFrames: clean.length,
      usableFrames: clean.filter((f) => f.ok).length,
      interpolatedFrames: clean.filter((f) => f.interpolated).length,
    },
    scoring: {
      formula: '동작 유사도 = 70% × 최저점 자세 + 30% × 궤적(100 × exp(−d̄ / 1.5))',
      tau: SCORE_TAU,
      note: '최저점 자세는 무릎(50%)·고관절(25%)·상체 숙임(25%)이 기준과 얼마나 다른지로 매깁니다 — 무릎 8°·고관절 10°·상체 8° 안쪽 차이는 감점하지 않습니다. d̄ 는 DTW 로 정렬한 궤적의 평균 거리입니다. 눈금·가중치는 실촬영 7개로 맞춘 설계값입니다.',
    },
  };
}
