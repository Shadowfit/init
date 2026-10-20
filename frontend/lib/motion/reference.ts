import { featuresFromJoints, rawVector, scaledVector, standingBaselines, normalizeFeatures } from './features';
import { JOINT_NAMES } from './types';
import type {
  FrameFeatures, MotionExercise, ReferenceCycle, SegmentId, Side, Vec3,
} from './types';

/**
 * 기준(시범) 모션 한 번을 비교용 시계열로 바꾼다.
 *
 * 시범 모션은 마네킹 장면(components/exercise/mannequin/sceneHtml.ts)의 키프레임 + 2-링크 IK 로
 * 움직인다. 그 장면이 «자기 자세 계산 그대로» 관절 위치를 뽑아 주고(compareExt.ts: sampleReference),
 * 여기서는 그 3D 관절에서 사용자와 같은 정의로 각도·높이 특징을 계산한다.
 */

/**
 * 비교에 쓰는 구간 — 시범의 앞뒤 «서 있는» 구간을 잘라낸다(사용자 반복도 같은 방식으로 다듬는다).
 * 값은 장면의 키프레임 표에서 읽었다: 스쿼트는 0.9초에 내려가기가 시작되고 3.9초에 올라오기가 끝나며,
 * 런지는 한 다리 4.8초 중 앞으로 딛기가 끝나는 1.2초부터 올라오기가 끝나는 3.4초까지가 «굽혔다 펴기» 다.
 * 앞뒤로 0.2~0.5초 여유를 둔다.
 */
export const REFERENCE_WINDOW: Record<MotionExercise, [number, number]> = {
  squat: [0.4, 4.1],
  lunge: [1.0, 3.6],
};

/** 장면이 붙여 주는 단계 이름(한국어) → 분석 구간. 앞의 «선 자세» 는 시작, 올라온 뒤의 것은 마무리다. */
export function segmentsOf(phases: string[]): SegmentId[] {
  let seenAscend = false;
  return phases.map((p) => {
    if (p.includes('내려가기') && !p.includes('돌아')) return 'descend';
    if (p.includes('바닥')) return 'bottom';
    if (p.includes('올라오기')) { seenAscend = true; return 'ascend'; }
    if (p.includes('돌아오기')) return 'end';
    return seenAscend ? 'end' : 'start';
  });
}

export interface PreparedReference {
  exercise: MotionExercise;
  lead: Side | null;
  /** 장면 시계에서 이 사이클이 시작하는 시각 — 장면에 «지금 이 시각의 자세» 를 시킬 때 t 에 더한다 */
  t0: number;
  /** 장면 마네킹의 몸통 길이(m)·발목 높이(m)·창 시작 시점의 엉덩이 중점 — 사용자 골격을 같은 크기·자리에 놓는 데 쓴다 */
  torso: number;
  ankleY: number;
  anchor: Vec3;
  /** 기준 마네킹의 관절 8개가 엉덩이 중점에서 좌우(x)로 떨어진 거리(m) — 사용자 골격의 좌우 폭을 이 비율로 맞춘다 */
  lateral: number[];
  /** 구간 안의 시범 시간(초) */
  t: number[];
  feats: FrameFeatures[];
  raw: number[][];
  scaled: number[][];
  segments: SegmentId[];
  phases: string[];
  durationSec: number;
  dt: number;
}

export function prepareReference(cycle: ReferenceCycle): PreparedReference {
  const [t0, t1] = REFERENCE_WINDOW[cycle.exercise];
  const all = cycle.samples;
  const segAll = segmentsOf(all.map((s) => s.phase));
  const keep: number[] = [];
  all.forEach((s, i) => { if (s.t >= t0 - 1e-9 && s.t <= t1 + 1e-9) keep.push(i); });
  if (keep.length < 4) throw new Error('기준 모션 샘플이 부족합니다');

  // 서 있을 때의 높이는 창 밖(앞뒤 서 있는 구간)까지 포함한 한 사이클 전체에서 읽는다
  const fullFeats = all.map((s) => featuresFromJoints(s.joints, s.t));
  if (fullFeats.some((f) => !f)) throw new Error('기준 모션의 특징을 계산하지 못했습니다');
  const baselines = standingBaselines(fullFeats as FrameFeatures[]);
  const feats: FrameFeatures[] = [];
  const t: number[] = [], phases: string[] = [], segments: SegmentId[] = [];
  for (const i of keep) {
    feats.push(normalizeFeatures(fullFeats[i] as FrameFeatures, baselines));
    t.push(all[i].t); phases.push(all[i].phase); segments.push(segAll[i]);
  }
  const lead = cycle.lead;
  const raw = feats.map((f) => rawVector(f, cycle.exercise, lead));
  const first = all[keep[0]].joints;
  return {
    exercise: cycle.exercise, lead,
    // 런지 왼발 사이클은 오른발 반 주기(4.8초) 뒤에서 시작한다(t0 가 없는 옛 기록 호환)
    t0: cycle.t0 ?? (cycle.exercise === 'lunge' && cycle.lead === 'left' ? 4.8 : 0),
    torso: cycle.torso, ankleY: cycle.ankleY,
    anchor: [(first.lHip[0] + first.rHip[0]) / 2, 0, (first.lHip[2] + first.rHip[2]) / 2],
    lateral: JOINT_NAMES.map((n) => first[n][0] - (first.lHip[0] + first.rHip[0]) / 2),
    t, feats, raw,
    scaled: raw.map((r) => scaledVector(r, cycle.exercise)),
    segments, phases,
    durationSec: t[t.length - 1] - t[0] + cycle.dt,
    dt: cycle.dt,
  };
}
