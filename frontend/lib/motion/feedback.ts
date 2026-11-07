import { mean } from './geometry';
import type { RepComparison, SessionComparison, SegmentSummary } from './analysis';
import { SEGMENT_LABEL, type JointName, type MotionExercise, type SegmentId } from './types';

/**
 * 비교 결과 → 문장. 모든 문장은 «실제로 잰 값» 에서만 만들고, 수치를 문장에 같이 싣는다.
 * 기준 동작과 다르다는 이유만으로 «위험하다» 고 쓰지 않는다 — 표현은 «기준보다 ~하다» 까지다.
 *
 * ⚠️ 아래 문턱은 실측으로 정한 값이 아니라 «이 정도 차이부터 말한다» 는 설계값이다. 단안 카메라 2D 각도는
 * 촬영 방향에 따라 몇 도씩 달라지므로 너무 작게 잡으면 측정 오차를 지적하게 된다.
 */
export const FEEDBACK_THRESHOLDS = {
  kneeDeg: 10,
  hipDeg: 12,
  trunkDeg: 8,
  /** 사용자 구간 시간 / 시범 구간 시간 이 이 값 이상이면 «느림», 이하면 «빠름» */
  slowRatio: 1.35,
  fastRatio: 0.74,
  /** 시간을 말하려면 구간에 프레임이 이만큼은 있어야 하고, 구간 길이가 프레임 간격의 이 배수 이상이어야 한다
   *  (경계는 프레임 사이를 보간해 읽으므로 오차가 프레임 간격 안팎이다 — 너무 짧은 구간의 시간은 말하지 않는다) */
  minSegmentFrames: 2,
  minSegmentIntervals: 2,
};

export type FeedbackKind = 'depth' | 'hip' | 'trunk' | 'timing';

export interface FeedbackItem {
  id: string;
  kind: FeedbackKind;
  /** 'check' = 기준과 눈에 띄게 다름, 'info' = 비슷함 */
  severity: 'check' | 'info';
  segment?: SegmentId;
  /** 3D 장면에서 강조할 관절 */
  joints: JointName[];
  text: string;
  /** 문장에 쓴 실제 측정값 */
  evidence: Record<string, number>;
}

interface Stats {
  exercise: MotionExercise;
  knee: { user: number; ref: number; delta: number };
  hip: { user: number; ref: number; delta: number };
  trunk: { user: number; ref: number; delta: number };
  segs: Pick<SegmentSummary, 'id' | 'frames' | 'userSec' | 'refSec'>[];
  /** 사용자 프레임 간격(초) */
  frameDt: number;
  /** 몇 회의 평균인가(0 이면 단일 반복) */
  reps: number;
  kneeJoints: JointName[];
  hipJoints: JointName[];
}

const r0 = (x: number) => Math.round(x);
const r1 = (x: number) => Math.round(x * 10) / 10;

function rulesFrom(s: Stats): FeedbackItem[] {
  const T = FEEDBACK_THRESHOLDS;
  const items: FeedbackItem[] = [];
  const prefix = s.reps > 1 ? `${s.reps}회 평균으로 ` : '';
  const kneeName = s.exercise === 'lunge' ? '앞다리 무릎' : '무릎';

  const kd = s.knee.delta;
  if (Math.abs(kd) >= T.kneeDeg) {
    items.push({
      id: 'knee-bottom', kind: 'depth', severity: 'check', segment: 'bottom', joints: s.kneeJoints,
      text: kd > 0
        ? `${prefix}최저점에서 ${kneeName} 각도가 기준 동작보다 약 ${r0(Math.abs(kd))}° 큽니다 (사용자 ${r0(s.knee.user)}° · 기준 ${r0(s.knee.ref)}°). 기준보다 덜 굽힌 동작입니다.`
        : `${prefix}최저점에서 ${kneeName} 각도가 기준 동작보다 약 ${r0(Math.abs(kd))}° 작습니다 (사용자 ${r0(s.knee.user)}° · 기준 ${r0(s.knee.ref)}°). 기준보다 더 깊게 굽힌 동작입니다.`,
      evidence: { user: s.knee.user, ref: s.knee.ref, delta: kd },
    });
  } else {
    items.push({
      id: 'knee-bottom', kind: 'depth', severity: 'info', segment: 'bottom', joints: s.kneeJoints,
      text: `${prefix}최저점에서 ${kneeName} 각도가 기준 동작과 비슷합니다 (차이 ${r0(Math.abs(kd))}°).`,
      evidence: { user: s.knee.user, ref: s.knee.ref, delta: kd },
    });
  }

  const hd = s.hip.delta;
  if (Math.abs(hd) >= T.hipDeg) {
    items.push({
      id: 'hip-bottom', kind: 'hip', severity: 'check', segment: 'bottom', joints: s.hipJoints,
      text: hd > 0
        ? `${prefix}최저점에서 고관절 각도가 기준 동작보다 약 ${r0(Math.abs(hd))}° 큽니다 (사용자 ${r0(s.hip.user)}° · 기준 ${r0(s.hip.ref)}°). 엉덩이를 기준만큼 접지 않았습니다.`
        : `${prefix}최저점에서 고관절 각도가 기준 동작보다 약 ${r0(Math.abs(hd))}° 작습니다 (사용자 ${r0(s.hip.user)}° · 기준 ${r0(s.hip.ref)}°). 엉덩이를 기준보다 더 접었습니다.`,
      evidence: { user: s.hip.user, ref: s.hip.ref, delta: hd },
    });
  }

  const td = s.trunk.delta;
  if (td >= T.trunkDeg) {
    items.push({
      id: 'trunk', kind: 'trunk', severity: 'check', joints: ['lShoulder', 'rShoulder'],
      text: `${prefix}상체가 기준 동작보다 앞으로 기울어지는 경향이 있습니다 (서 있을 때보다 최대 사용자 ${r0(s.trunk.user)}° · 기준 ${r0(s.trunk.ref)}° 더 숙임, 약 ${r0(td)}° 차이).`,
      evidence: { user: s.trunk.user, ref: s.trunk.ref, delta: td },
    });
  } else if (td <= -T.trunkDeg) {
    items.push({
      id: 'trunk', kind: 'trunk', severity: 'check', joints: ['lShoulder', 'rShoulder'],
      text: `${prefix}상체를 기준 동작보다 더 세운 채 수행했습니다 (서 있을 때보다 최대 사용자 ${r0(s.trunk.user)}° · 기준 ${r0(s.trunk.ref)}° 더 숙임, 약 ${r0(Math.abs(td))}° 차이).`,
      evidence: { user: s.trunk.user, ref: s.trunk.ref, delta: td },
    });
  } else {
    items.push({
      id: 'trunk', kind: 'trunk', severity: 'info', joints: ['lShoulder', 'rShoulder'],
      text: `${prefix}상체 숙임이 기준 동작과 비슷합니다 (서 있을 때 대비 최대 숙임 차이 ${r0(Math.abs(td))}°).`,
      evidence: { user: s.trunk.user, ref: s.trunk.ref, delta: td },
    });
  }

  for (const seg of s.segs) {
    if (seg.id !== 'descend' && seg.id !== 'ascend') continue;
    if (seg.frames < T.minSegmentFrames || seg.refSec <= 0 || seg.userSec < T.minSegmentIntervals * s.frameDt) continue;
    const ratio = seg.userSec / seg.refSec;
    const label = `${SEGMENT_LABEL[seg.id]} 구간`;
    if (ratio >= T.slowRatio) {
      items.push({
        id: `timing-${seg.id}`, kind: 'timing', severity: 'check', segment: seg.id, joints: [],
        text: `${prefix}${label}을 시범 모션보다 느리게 수행했습니다 (${r1(seg.userSec)}초 · 시범 ${r1(seg.refSec)}초).`,
        evidence: { userSec: seg.userSec, refSec: seg.refSec, ratio },
      });
    } else if (ratio <= T.fastRatio) {
      items.push({
        id: `timing-${seg.id}`, kind: 'timing', severity: 'check', segment: seg.id, joints: [],
        text: `${prefix}${label}을 시범 모션보다 빠르게 수행했습니다 (${r1(seg.userSec)}초 · 시범 ${r1(seg.refSec)}초).`,
        evidence: { userSec: seg.userSec, refSec: seg.refSec, ratio },
      });
    }
  }
  return items;
}

function jointsOf(rep: RepComparison, key: string): JointName[] {
  return rep.metrics.find((m) => m.key === key)?.joints ?? [];
}

function statsOfRep(exercise: MotionExercise, rep: RepComparison): Stats {
  return {
    exercise, knee: rep.kneeMin, hip: rep.hipMin, trunk: rep.trunkMax,
    segs: rep.segments, frameDt: rep.frameDt, reps: 0,
    kneeJoints: exercise === 'squat' ? ['lKnee', 'rKnee'] : jointsOf(rep, 'leadKnee'),
    hipJoints: exercise === 'squat' ? ['lHip', 'rHip'] : jointsOf(rep, 'leadHip'),
  };
}

/** 반복 하나에 대한 문장 */
export function feedbackForRep(exercise: MotionExercise, rep: RepComparison): FeedbackItem[] {
  return rulesFrom(statsOfRep(exercise, rep));
}

/** 분석된 반복 전체의 평균으로 만든 문장 */
export function feedbackForSession(cmp: SessionComparison): FeedbackItem[] {
  const reps = cmp.reps.filter((r): r is RepComparison => r.status === 'ok');
  if (!reps.length) return [];
  const avgEx = (pick: (r: RepComparison) => { user: number; ref: number; delta: number }) => ({
    user: mean(reps.map((r) => pick(r).user)),
    ref: mean(reps.map((r) => pick(r).ref)),
    delta: mean(reps.map((r) => pick(r).delta)),
  });
  const ids: SegmentId[] = ['start', 'descend', 'bottom', 'ascend', 'end'];
  const segs = ids.map((id) => {
    const per = reps.map((r) => r.segments.find((s) => s.id === id)!);
    return {
      id,
      frames: Math.round(mean(per.map((s) => s.frames))),
      userSec: mean(per.map((s) => s.userSec)),
      refSec: mean(per.map((s) => s.refSec)),
    };
  });
  return rulesFrom({
    exercise: cmp.exercise,
    knee: avgEx((r) => r.kneeMin), hip: avgEx((r) => r.hipMin), trunk: avgEx((r) => r.trunkMax),
    segs, frameDt: mean(reps.map((r) => r.frameDt)), reps: reps.length,
    // 런지는 반복마다 앞다리가 바뀔 수 있어 평균 문장에서는 양쪽 관절을 모두 가리킨다
    kneeJoints: ['lKnee', 'rKnee'], hipJoints: ['lHip', 'rHip'],
  });
}
