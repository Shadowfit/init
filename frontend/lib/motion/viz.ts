import { median, mean } from './geometry';
import { cleanRecording, type CleanFrame } from './preprocess';
import type { RepComparison, ReferenceSet } from './analysis';
import type { PreparedReference } from './reference';
import { JOINT_NAMES, LM, SEGMENT_LABEL, type JointName, type MotionRecording, type SegmentId, type Vec3 } from './types';

/**
 * 비교 결과 → 3D 장면에 넣을 데이터 (사용자 골격 좌표·기준 시각·관절별 오차).
 *
 * ⚠️ 이 골격은 «카메라 화면의 2D 동작을 3D 장면에 옮긴 것» 이다. 높이(y)와 앞뒤(화면 가로축)는 이미지 좌표 그대로이고,
 * MediaPipe 가 추정하는 깊이(z)는 단안 카메라에서 오차가 커서(45° 촬영에서 골격이 누워 보일 만큼) 쓰지 않는다.
 * 좌우 폭은 기준 마네킹 비율로 고정한다. 그래서 정면에 가까운 촬영일수록 실제 앞뒤 움직임보다 작게 그려진다 —
 * 화면에는 이 점을 밝힌다. 실제 깊이를 잰 것처럼 보이지 않게 하는 것이 목적이다. 비교 점수·피드백은 이 좌표가 아니라 각도(2D)로 계산한다.
 */
export interface MotionPayload {
  repNumber: number;
  /** 사용자 프레임: 시각(초)과 관절 8개 × (x,y,z) — 장면 좌표(m) */
  frames: Array<{ t: number; p: number[] }>;
  /** 사용자 프레임마다 대응한 기준 시각(사이클 안, 초) — 장면에는 refOffset 을 더해 넘긴다 */
  refTime: number[];
  refOffset: number;
  /** 프레임별 관절 8개의 오차 크기 0~1.2 (1 = 눈에 띄게 다름) */
  err: number[][];
  segment: SegmentId[];
  /** 화면에 같이 보여 줄 한 줄 안내 */
  note: string;
}

const IDX: number[] = [
  LM.LEFT_SHOULDER, LM.RIGHT_SHOULDER, LM.LEFT_HIP, LM.RIGHT_HIP,
  LM.LEFT_KNEE, LM.RIGHT_KNEE, LM.LEFT_ANKLE, LM.RIGHT_ANKLE,
];
/** 오차를 색(초록→빨강)으로 바꿀 때 «빨강 = 1» 이 되는 크기. 시각화용 눈금이다(점수·문장의 문턱과 별개). */
const VIS_SCALE: Record<string, number> = {
  knee: 15, hip: 20, lean: 12, drop: 0.15,
  leadKnee: 15, trailKnee: 15, leadHip: 20, trailHip: 20,
};

function lm3(clean: CleanFrame, aspect: number): Vec3[] {
  // 이미지 높이를 1로 두는 단위: x 에 비율을 곱하고, y 는 뒤집는다(위가 +)
  return IDX.map((i) => {
    const p = clean.lm![i];
    return [(p[0] - 0.5) * aspect, -(p[1] - 0.5), 0] as Vec3;   // 깊이(z)는 쓰지 않는다 — 아래 설명
  });
}

export function buildMotionPayload(
  clean: CleanFrame[], aspect: number, rep: RepComparison, ref: PreparedReference,
): MotionPayload {
  const raw = rep.frameIndex.map((i) => lm3(clean[i], aspect));
  const mid = (a: Vec3, b: Vec3): Vec3 => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];
  const torsoLens = raw.map((p) => {
    const s = mid(p[0], p[1]), h = mid(p[2], p[3]);
    return Math.hypot(s[0] - h[0], s[1] - h[1], s[2] - h[2]);
  });
  // 몸통은 숙이면 화면에서 짧아 보이므로, 중앙값이 아니라 «서 있을 때에 가까운» 긴 쪽(75 백분위)으로 크기를 맞춘다
  const sortedTorso = [...torsoLens].sort((a, b) => a - b);
  const scale = ref.torso / Math.max(sortedTorso[Math.min(sortedTorso.length - 1, Math.floor(sortedTorso.length * 0.75))], 1e-6);
  const hip0 = mid(raw[0][2], raw[0][3]);
  const floor = median(raw.map((p) => Math.min(p[6][1], p[7][1])));

  // 1) 크기·자리 맞추기 — 몸통 길이를 기준 마네킹과 같게, 첫 프레임 엉덩이를 원점, 발 높이를 바닥으로
  const placed = raw.map((p) => p.map((v): Vec3 => [(v[0] - hip0[0]) * scale, (v[1] - floor) * scale + ref.ankleY, (v[2] - hip0[2]) * scale]));

  // 2) 앞뒤 방향 정하기 — 깊이(z)는 쓰지 않는다. 화면의 가로축을 «앞뒤(시상면)» 로 보고, 가장 깊이 앉은 프레임에서
  //    무릎이 엉덩이–발목 선보다 나온 쪽을 «앞» 으로 삼는다. 이렇게 그리면 골격의 각도가 점수를 낸 2D 각도와 같다.
  let deepest = 0, lowest = Infinity;
  placed.forEach((p, k) => {
    const h = (p[2][1] + p[3][1]) / 2;   // 엉덩이가 가장 낮은 프레임 = 가장 깊이 앉은 순간
    if (h < lowest) { lowest = h; deepest = k; }
  });
  const dp = placed[deepest];
  const kneeOffset = [[2, 4, 6], [3, 5, 7]].reduce((sum, [h, k, a]) => sum + dp[k][0] - (dp[h][0] + dp[a][0]) / 2, 0);
  const facing = kneeOffset >= 0 ? 1 : -1;
  const rotatedRaw = placed.map((p) => {
    const q = p.map((v): Vec3 => [0, v[1], facing * v[0]]);
    // 발은 바닥에 붙어 있다 — 45° 촬영에서 가까운 다리와 먼 다리의 발목이 화면에서 10cm 넘게 다른 높이로 찍히는 것은 원근 착시다
    q[6][1] = ref.ankleY; q[7][1] = ref.ankleY;
    if (ref.exercise === 'squat') {
      // 스쿼트는 좌우 대칭 동작이라 같은 쌍은 평균을 쓴다(한쪽 다리가 카메라에 더 가까워 생기는 어긋남을 없앤다)
      for (const [l, r] of [[0, 1], [2, 3], [4, 5], [6, 7]]) {
        const y = (q[l][1] + q[r][1]) / 2, z = (q[l][2] + q[r][2]) / 2;
        q[l] = [0, y, z]; q[r] = [0, y, z];
      }
    }
    return q;
  });

  // 3) 좌우 폭 맞추기 — 단안 카메라는 좌우(옆으로 벌어진) 정보가 약하고 측면 촬영이면 양쪽이 겹쳐 보인다.
  //    이 화면이 비교하는 것은 앞뒤·위아래(시상면) 동작이므로, 좌우 위치는 기준 마네킹의 몸 폭으로 고정하고
  //    관절 이름(왼/오른)으로 좌우를 정한다. 앞뒤(z)·높이(y)만 사용자의 측정값을 쓴다.
  const rotated = rotatedRaw.map((p) => p.map((v, k): Vec3 => [ref.lateral[k], v[1], v[2]]));

  // 4) 기준 마네킹 엉덩이 자리에 맞춘다
  const frames = rotated.map((p, k) => ({
    t: Math.round(rep.userT[k] * 1000) / 1000,
    p: p.flatMap((v) => [Math.round((v[0] + ref.anchor[0]) * 10000) / 10000, Math.round(v[1] * 10000) / 10000, Math.round((v[2] + ref.anchor[2]) * 10000) / 10000]),
  }));

  // 5) 관절별 오차 0~1.2
  const err = rep.frameError.map((e) => {
    const out = new Array<number>(JOINT_NAMES.length).fill(0);
    for (const m of rep.metrics) {
      const v = Math.abs(e[m.key]) / (VIS_SCALE[m.key] ?? 15);
      for (const j of m.joints) {
        const k = JOINT_NAMES.indexOf(j as JointName);
        if (k >= 0) out[k] = Math.max(out[k], Math.min(1.2, v));
      }
    }
    return out.map((x) => Math.round(x * 1000) / 1000);
  });

  return {
    repNumber: rep.repNumber, frames, refTime: rep.refTime, refOffset: ref.t0, err, segment: rep.segment,
    note: '파란 골격은 카메라 화면의 동작을 옮긴 것입니다. 깊이는 측정하지 않으며, 촬영 방향에 따라 실제보다 작게 보일 수 있어요.',
  };
}

/** 분석된 반복마다 장면용 데이터를 만든다. */
export function buildPayloads(
  rec: MotionRecording, reps: Array<RepComparison | { status: 'skipped'; repNumber: number }>, refs: ReferenceSet,
): MotionPayload[] {
  const clean = cleanRecording(rec);
  const out: MotionPayload[] = [];
  for (const r of reps) {
    if (r.status !== 'ok') continue;
    const ref = rec.exercise === 'squat' ? refs.squat : (r.lead ? refs.lunge?.[r.lead] : undefined);
    if (ref) out.push(buildMotionPayload(clean, rec.aspect, r, ref));
  }
  return out;
}

export function segmentLabel(id: SegmentId): string { return `${SEGMENT_LABEL[id]} 구간`; }
