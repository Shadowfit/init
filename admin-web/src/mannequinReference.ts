// 3D 마네킹 시범 → AI 싱크로율 기준 좌표. 앱의 3D 비교 장면(frontend/components/exercise/mannequin, compare 모드)이
// 보내는 ReferenceCycle(관절 8개의 3D 위치 시계열)을 «가상 랜드마크» 프레임으로 바꿔 PUT /admin/exercises/{id}/reference-poses 에 보낸다.
//
// 왜 영상(mp4)이 아니라 관절인가: 마네킹을 영상으로 찍어 MediaPipe 로 다시 읽으면 무릎이 벌어진 자세의 투영 때문에
// 2D 무릎각이 실제(71°)보다 20° 넘게 얕게 읽히고, 45° 에서는 런지로 오인돼 반복이 무효가 된다. 관절을 시상면(앞뒤·높이)에
// 같은 배율로 투영하면 2D 각도 ≈ 3D 각도(스쿼트 최저 무릎 71.3° vs 71.8°)이고, 앱의 3D 비교가 쓰는 기준과 같은 숫자가 된다.
// (scripts/mannequin-reference/make-ref-sql.cjs 와 같은 변환)
export type Vec3 = [number, number, number];
export interface ReferenceSample { t: number; phase: string; joints: Record<JointName, Vec3> }
export type JointName = 'lShoulder' | 'rShoulder' | 'lHip' | 'rHip' | 'lKnee' | 'rKnee' | 'lAnkle' | 'rAnkle';
export interface ReferenceCycle { exercise: 'squat' | 'lunge'; lead: 'left' | 'right' | null; dt: number; t0?: number; samples: ReferenceSample[] }

export interface ReferenceFrame { timestampSec: number; jointCoordinates: string }
export const MANNEQUIN_SOURCE = 'mannequin-3d-joints';

/** MediaPipe 랜드마크 인덱스 */
const LM: Record<JointName, number> = { lShoulder: 11, rShoulder: 12, lHip: 23, rHip: 24, lKnee: 25, rKnee: 26, lAnkle: 27, rAnkle: 28 };

/**
 * 투영 배율 — 앞뒤(z)와 높이(y)에 **같은 배율**을 써야 2D 각도가 시상면 각도와 같다(너비/높이 비율 1.0 표식).
 * 2026-10-21 이전엔 앞뒤 0.35 / 높이 0.45 로 달라서 AI 가 스쿼트 최저 무릎을 실제 71°가 아닌 77°로 읽었다
 * (기준이 6° 얕아져 후해짐). 앞뒤 범위는 스쿼트 ±0.2m·런지 ±0.6m 남짓이라 x 가 0~1 안에 든다.
 */
const PROJ_SCALE = 0.45;

/** 한 사이클의 관절 → 프레임 목록. x = 앞뒤(z), y = 높이(위가 0), 너비/높이 1.0 표식을 맨 뒤에 붙인다. */
export function cycleToFrames(cycle: ReferenceCycle): ReferenceFrame[] {
  return cycle.samples.map((s) => {
    const items: Array<Record<string, number | string>> = (Object.keys(LM) as JointName[]).map((n) => {
      const p = s.joints[n];
      return { index: LM[n], x: +(0.5 + p[2] * PROJ_SCALE).toFixed(4), y: +(0.95 - p[1] * PROJ_SCALE).toFixed(4), z: +p[0].toFixed(4), visibility: 1 };
    });
    items.push({ index: -1, x: 0, y: 0, z: 0, visibility: 0, image_aspect_ratio: 1.0, source: MANNEQUIN_SOURCE });
    return { timestampSec: Math.round(s.t * 1000) / 1000, jointCoordinates: JSON.stringify(items) };
  });
}

/** 시상면(앞뒤 z · 높이 y) 2D 각도 — AI 가 이 기준 좌표에서 재는 각도와 같다(서버 요약 minKneeDeg 도 같은 값) */
function angleAt(a: Vec3, b: Vec3, c: Vec3): number {
  const u = [a[2] - b[2], a[1] - b[1]], v = [c[2] - b[2], c[1] - b[1]];
  const n = Math.hypot(u[0], u[1]) * Math.hypot(v[0], v[1]);
  if (n < 1e-9) return NaN;
  return (Math.acos(Math.max(-1, Math.min(1, (u[0] * v[0] + u[1] * v[1]) / n))) * 180) / Math.PI;
}

/** 미리보기용 요약 — 최저 무릎각·고관절각(좌우 평균, 시상면 2D), 길이(초) */
export function summarizeCycle(cycle: ReferenceCycle): { minKnee: number; minHip: number; seconds: number } {
  let minKnee = Infinity, minHip = Infinity;
  for (const s of cycle.samples) {
    const j = s.joints;
    minKnee = Math.min(minKnee, (angleAt(j.lHip, j.lKnee, j.lAnkle) + angleAt(j.rHip, j.rKnee, j.rAnkle)) / 2);
    minHip = Math.min(minHip, (angleAt(j.lShoulder, j.lHip, j.lKnee) + angleAt(j.rShoulder, j.rHip, j.rKnee)) / 2);
  }
  const last = cycle.samples[cycle.samples.length - 1];
  // 서버 요약(durationSec)과 같은 정의 — 첫 프레임부터 마지막 프레임까지
  return { minKnee, minHip, seconds: last ? last.t - cycle.samples[0].t : 0 };
}

/** 종목 코드 → 마네킹 시범이 있는 종목. 없으면 null */
export function mannequinExerciseOf(code: string | null): 'squat' | 'lunge' | null {
  if (code === 'SQUAT') return 'squat';
  if (code === 'LUNGE') return 'lunge';
  return null;
}
