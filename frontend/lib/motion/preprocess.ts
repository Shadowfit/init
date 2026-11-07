import { LM, type LandmarkTuple, type MotionRecording, type RecordedFrame, type Side } from './types';

/**
 * 좌표 기록 전처리 — 신뢰도 낮은 프레임 걸러내기, 짧은 끊김 메우기, 떨림 줄이기.
 *
 * ⚠️ 아래 상수는 실측으로 정한 값이 아니라 «정한 값» 이다 (이 앱의 AI 서버는 가시성 0.55~0.6 을 쓴다 —
 * squat_counter.SquatThresholds.visibility, lunge_analyzer.LungeThresholds.visibility. 여기는 한
 * 단계 느슨하게 둬서, 서버가 판정에 쓴 프레임을 비교에서 버리는 일이 없게 했다).
 */
export const MIN_VISIBILITY = 0.5;
/** 이 프레임 수까지의 끊김은 앞뒤 값으로 선형 보간한다(3fps 에서 약 0.67초). 그보다 길면 메우지 않는다. */
export const MAX_GAP_FRAMES = 2;
/** 이 시간 이상 끊기면 필터를 다시 시작한다(떨림 필터가 오래된 값에 끌려가지 않도록). */
const FILTER_RESET_SEC = 1.0;
/** One-Euro 필터 (Casiez et al. 2012). 좌표가 0~1 정규화라 beta 가 보통(픽셀 기준)보다 크다. */
const MIN_CUTOFF_HZ = 1.0;
const BETA = 6.0;
const D_CUTOFF_HZ = 1.0;

const CHAINS: ReadonlyArray<readonly [number, number, number, number]> = [
  [LM.LEFT_SHOULDER, LM.LEFT_HIP, LM.LEFT_KNEE, LM.LEFT_ANKLE],
  [LM.RIGHT_SHOULDER, LM.RIGHT_HIP, LM.RIGHT_KNEE, LM.RIGHT_ANKLE],
];

export interface CleanFrame {
  t: number;
  reps: number;
  lead: Side | null;
  /** 쓸 수 있는 프레임인가 (한쪽 다리 사슬이라도 신뢰도를 넘는다) */
  ok: boolean;
  /** 끊김을 보간으로 메운 프레임 */
  interpolated: boolean;
  lm: LandmarkTuple[] | null;
}

/** 한쪽이라도 어깨-엉덩이-무릎-발목이 모두 보이는가 */
export function chainVisible(lm: LandmarkTuple[] | null, side: 0 | 1): boolean {
  if (!lm || lm.length < 33) return false;
  return CHAINS[side].every((i) => {
    const p = lm[i];
    return !!p && Number.isFinite(p[0]) && Number.isFinite(p[1]) && p[3] >= MIN_VISIBILITY;
  });
}

function usable(lm: LandmarkTuple[] | null): boolean {
  return chainVisible(lm, 0) || chainVisible(lm, 1);
}

function lerpLandmarks(a: LandmarkTuple[], b: LandmarkTuple[], u: number): LandmarkTuple[] {
  return a.map((p, i) => {
    const q = b[i];
    return [
      p[0] + (q[0] - p[0]) * u,
      p[1] + (q[1] - p[1]) * u,
      p[2] + (q[2] - p[2]) * u,
      Math.min(p[3], q[3]),
    ] as LandmarkTuple;
  });
}

class OneEuro {
  private x: number | null = null;
  private dx = 0;
  private t = 0;

  reset(): void { this.x = null; this.dx = 0; }

  private static alpha(cutoff: number, dt: number): number {
    const tau = 1 / (2 * Math.PI * cutoff);
    return 1 / (1 + tau / dt);
  }

  filter(value: number, t: number): number {
    if (this.x === null) { this.x = value; this.t = t; this.dx = 0; return value; }
    const dt = t - this.t;
    if (!(dt > 0)) return this.x;
    const dxRaw = (value - this.x) / dt;
    this.dx += OneEuro.alpha(D_CUTOFF_HZ, dt) * (dxRaw - this.dx);
    const cutoff = MIN_CUTOFF_HZ + BETA * Math.abs(this.dx);
    this.x += OneEuro.alpha(cutoff, dt) * (value - this.x);
    this.t = t;
    return this.x;
  }
}

/**
 * 기록을 정리한다: ① 신뢰도 판정 ② 짧은 끊김 보간 ③ One-Euro 평활화.
 * 입력은 바꾸지 않는다. 반환 길이는 입력과 같다(프레임을 버리지 않고 ok=false 로 표시).
 */
export function cleanRecording(rec: MotionRecording): CleanFrame[] {
  const frames: RecordedFrame[] = rec.frames;
  const out: CleanFrame[] = frames.map((f) => ({
    t: f.t, reps: f.reps, lead: f.lead,
    ok: usable(f.lm), interpolated: false,
    lm: f.lm ? f.lm.map((p) => [...p] as LandmarkTuple) : null,
  }));

  // ② 짧은 끊김 보간
  let i = 0;
  while (i < out.length) {
    if (out[i].ok) { i++; continue; }
    let j = i;
    while (j < out.length && !out[j].ok) j++;
    const gap = j - i;
    const before = i - 1, after = j;
    if (gap <= MAX_GAP_FRAMES && before >= 0 && after < out.length && out[before].lm && out[after].lm) {
      const span = out[after].t - out[before].t;
      for (let k = i; k < j; k++) {
        const u = span > 0 ? (out[k].t - out[before].t) / span : 0.5;
        out[k].lm = lerpLandmarks(out[before].lm!, out[after].lm!, u);
        out[k].ok = true;
        out[k].interpolated = true;
      }
    }
    i = j;
  }

  // ③ 평활화 — 쓸 수 있는 프레임만, 시간 순서대로. 점마다 x·y·z 를 따로 거른다.
  const filters: OneEuro[][] = Array.from({ length: 33 }, () => [new OneEuro(), new OneEuro(), new OneEuro()]);
  let lastT = -Infinity;
  for (const f of out) {
    if (!f.ok || !f.lm) continue;
    if (f.t - lastT > FILTER_RESET_SEC) filters.forEach((fs) => fs.forEach((x) => x.reset()));
    f.lm = f.lm.map((p, idx) => {
      const fs = filters[idx];
      return [fs[0].filter(p[0], f.t), fs[1].filter(p[1], f.t), fs[2].filter(p[2], f.t), p[3]] as LandmarkTuple;
    });
    lastT = f.t;
  }
  return out;
}
