import { angleAt, len, mid, sub, tiltFromUp } from './geometry';
import { chainVisible } from './preprocess';
import {
  LM, type FrameFeatures, type Joints3D, type LandmarkTuple, type MotionExercise, type Side, type Vec3,
} from './types';

/**
 * 프레임 → 특징(관절 각도 + 정규화한 상대 좌표).
 *
 * 사용자(카메라 좌표)와 기준(마네킹 3D)이 같은 정의를 쓰도록 «점 6개 → 특징» 계산을 한 곳에 둔다.
 *  - 각도는 길이 단위와 무관하다.
 *  - 엉덩이 높이는 몸통 길이로 나눈다 → 키·카메라 거리와 무관한 값.
 * 사용자 쪽은 이미지 평면 2D 로 잰다(AI 서버의 각도 계산과 같다 — z 는 단안 카메라 추정이라 섞지 않는다).
 */
interface PointSet {
  shoulder: [Vec3, Vec3]; hip: [Vec3, Vec3]; knee: [Vec3, Vec3]; ankle: [Vec3, Vec3];
  /** 좌우 중 몸이 보이는 쪽 (0=left, 1=right) */
  visible: [boolean, boolean];
}

function compute(ps: PointSet, t: number): FrameFeatures | null {
  const sides: (0 | 1)[] = [];
  if (ps.visible[0]) sides.push(0);
  if (ps.visible[1]) sides.push(1);
  if (!sides.length) return null;
  const knee = [NaN, NaN], hip = [NaN, NaN];
  for (const s of sides) {
    knee[s] = angleAt(ps.hip[s], ps.knee[s], ps.ankle[s]);
    hip[s] = angleAt(ps.shoulder[s], ps.hip[s], ps.knee[s]);
  }
  // 한쪽만 보이면 같은 값을 반대쪽에 쓴다(AI 서버의 observe_squat 과 같은 처리)
  const only = sides.length === 1 ? sides[0] : null;
  if (only !== null) { knee[1 - only] = knee[only]; hip[1 - only] = hip[only]; }
  if ([...knee, ...hip].some((v) => !Number.isFinite(v))) return null;

  const avg = (pts: [Vec3, Vec3]): Vec3 => {
    const a = sides.map((s) => pts[s]);
    return a.length === 1 ? a[0] : mid(a[0], a[1]);
  };
  const shoulder = avg(ps.shoulder), hipMid = avg(ps.hip), ankleMid = avg(ps.ankle);
  const torso = len(sub(shoulder, hipMid));
  if (torso < 1e-6) return null;
  const trunk = tiltFromUp(sub(shoulder, hipMid));
  const hipH = (hipMid[1] - ankleMid[1]) / torso;
  if (!Number.isFinite(trunk) || !Number.isFinite(hipH)) return null;
  return { t, kneeL: knee[0], kneeR: knee[1], hipL: hip[0], hipR: hip[1], trunk, hipH, drop: NaN, lean: NaN };
}

/** 사용자 프레임: 랜드마크 → 이미지 평면 2D(y 위쪽, x 는 비율 보정). */
export function featuresFromLandmarks(lm: LandmarkTuple[], aspect: number, t: number): FrameFeatures | null {
  if (!Number.isFinite(aspect) || aspect <= 0) return null;
  const P = (i: number): Vec3 => [lm[i][0] * aspect, -lm[i][1], 0];
  return compute({
    shoulder: [P(LM.LEFT_SHOULDER), P(LM.RIGHT_SHOULDER)],
    hip: [P(LM.LEFT_HIP), P(LM.RIGHT_HIP)],
    knee: [P(LM.LEFT_KNEE), P(LM.RIGHT_KNEE)],
    ankle: [P(LM.LEFT_ANKLE), P(LM.RIGHT_ANKLE)],
    visible: [chainVisible(lm, 0), chainVisible(lm, 1)],
  }, t);
}

/** 기준 프레임: 마네킹의 3D 관절 위치. 양쪽이 다 있다. */
export function featuresFromJoints(j: Joints3D, t: number): FrameFeatures | null {
  return compute({
    shoulder: [j.lShoulder, j.rShoulder], hip: [j.lHip, j.rHip],
    knee: [j.lKnee, j.rKnee], ankle: [j.lAnkle, j.rAnkle],
    visible: [true, true],
  }, t);
}

/** DTW 에 넣는 특징 한 칸의 정의. scale 은 «이만큼 다르면 거리 1» 인 눈금이다. */
export interface FeatureDef {
  key: string;
  label: string;
  unit: '°' | '';
  scale: number;
  weight: number;
}

/**
 * ⚠️ scale·weight 는 실측으로 정한 값이 아니라 설계값이다.
 *  - scale: 각도 30° 차이, 몸통 기울기 15° 차이, 엉덩이 내려간 정도 0.2(서 있을 때 높이의 20%) 차이를 «거리 1» 로 둔다.
 *  - weight: 운동의 핵심 관절(무릎)을 1.0 으로 두고, 엉덩이·몸통·높이는 이보다 낮게 줬다.
 *    런지 뒷다리 무릎은 앞다리보다 덜 중요해서 0.6.
 * 실촬영으로 정상/얕은 동작이 갈리는지만 확인했고(run-tests.cjs), 값 자체의 최적성은 검증 전이다.
 */
export const FEATURE_DEFS: Record<MotionExercise, FeatureDef[]> = {
  squat: [
    { key: 'knee', label: '무릎 각도', unit: '°', scale: 30, weight: 1.0 },
    { key: 'hip', label: '고관절 각도', unit: '°', scale: 30, weight: 0.8 },
    { key: 'lean', label: '상체 숙임', unit: '°', scale: 15, weight: 0.8 },
    { key: 'drop', label: '엉덩이 내려간 정도', unit: '', scale: 0.2, weight: 0.6 },
  ],
  lunge: [
    { key: 'leadKnee', label: '앞다리 무릎 각도', unit: '°', scale: 30, weight: 1.0 },
    { key: 'trailKnee', label: '뒷다리 무릎 각도', unit: '°', scale: 30, weight: 0.6 },
    { key: 'lean', label: '상체 숙임', unit: '°', scale: 15, weight: 0.8 },
    { key: 'drop', label: '엉덩이 내려간 정도', unit: '', scale: 0.2, weight: 0.6 },
  ],
};

/** 프레임 특징 → DTW 입력 값(눈금 보정 전). 런지는 앞다리/뒷다리로 나눠 좌우 방향에 의존하지 않게 한다. */
export function rawVector(f: FrameFeatures, exercise: MotionExercise, lead: Side | null): number[] {
  if (exercise === 'squat') {
    return [(f.kneeL + f.kneeR) / 2, (f.hipL + f.hipR) / 2, f.lean, f.drop];
  }
  const leadLeft = lead !== 'right';
  return [leadLeft ? f.kneeL : f.kneeR, leadLeft ? f.kneeR : f.kneeL, f.lean, f.drop];
}

export function scaledVector(raw: number[], exercise: MotionExercise): number[] {
  const defs = FEATURE_DEFS[exercise];
  return raw.map((v, i) => v / defs[i].scale);
}

export function weightsOf(exercise: MotionExercise): number[] {
  return FEATURE_DEFS[exercise].map((d) => d.weight);
}

/** 한 사람(또는 기준 모션)의 «서 있을 때» 기준선 — 엉덩이 높이와 상체 기울기. */
export interface Baselines { hipH: number; trunk: number }

/**
 * 기준선 계산. 엉덩이 높이는 «높은 쪽 값» 의 90 백분위(최댓값을 쓰면 잡음 한 프레임에 기준선이 틀어진다),
 * 상체 기울기는 무릎이 거의 펴진 프레임(평균 ≥ 160°)의 중앙값 — 그런 프레임이 없으면 가장 작은 10 백분위.
 */
export function standingBaselines(feats: FrameFeatures[]): Baselines {
  const hs = feats.map((f) => f.hipH).filter(Number.isFinite).sort((a, b) => a - b);
  const standing = feats.filter((f) => (f.kneeL + f.kneeR) / 2 >= 160).map((f) => f.trunk).filter(Number.isFinite).sort((a, b) => a - b);
  const ts = feats.map((f) => f.trunk).filter(Number.isFinite).sort((a, b) => a - b);
  const pick = (xs: number[], q: number) => (xs.length ? xs[Math.min(xs.length - 1, Math.floor(xs.length * q))] : NaN);
  return {
    hipH: pick(hs, 0.9),
    trunk: standing.length >= 2 ? standing[standing.length >> 1] : pick(ts, 0.1),
  };
}

/** 기준선을 빼고 나눠 사람·카메라 차이를 없앤 특징으로 바꾼다 (drop·lean 을 채운다). */
export function normalizeFeatures(f: FrameFeatures, b: Baselines): FrameFeatures {
  return {
    ...f,
    drop: Number.isFinite(b.hipH) && b.hipH > 0 ? 1 - f.hipH / b.hipH : NaN,
    lean: Number.isFinite(b.trunk) ? f.trunk - b.trunk : NaN,
  };
}
