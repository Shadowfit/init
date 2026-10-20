/* [합성] 시험용 스쿼트 골격·기록 생성기 — 정답을 알고 있는 데이터로 파이프라인을 잰다. */
// ── [합성] 측면 스쿼트 골격 ──────────────────────────────────────────────────
const THIGH = 0.45, SHIN = 0.45, TORSO = 0.5;
/** d: 0(서기)~1(최저점), minKnee: 최저점 무릎 각도 */
function squatShape(d, minKnee, trunkMax) {
  const knee = 175 - (175 - minKnee) * d;
  const trunk = 5 + (trunkMax - 5) * d;
  const s = (8 + 20 * d) * Math.PI / 180;             // 정강이가 앞으로 기운 각
  const ankle = [0, 0];
  const kneeP = [SHIN * Math.sin(s), SHIN * Math.cos(s)];
  const v1 = [ankle[0] - kneeP[0], ankle[1] - kneeP[1]];   // 무릎→발목
  const th = knee * Math.PI / 180;
  const rot = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a)];
  const c1 = rot(v1, th), c2 = rot(v1, -th);
  const dir = c1[0] < c2[0] ? c1 : c2;                       // 엉덩이는 무릎보다 뒤
  const n = Math.hypot(dir[0], dir[1]);
  const hip = [kneeP[0] + dir[0] / n * THIGH, kneeP[1] + dir[1] / n * THIGH];
  const tr = trunk * Math.PI / 180;
  const shoulder = [hip[0] + TORSO * Math.sin(tr), hip[1] + TORSO * Math.cos(tr)];
  return { ankle, knee: kneeP, hip, shoulder };
}
/** 시범 모션 키프레임: 서기 0.9 / 내려가기 1.3 / 바닥 0.5 / 올라오기 1.2 / 서기 0.3 (마네킹 장면의 SQUAT 표와 같은 모양) */
const KEY = [[0.9, 0, 0], [1.3, 0, 1], [0.5, 1, 1], [1.2, 1, 0], [0.3, 0, 0]];
const ease = (x) => { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); };
function depthAt(t, scaleTime = [1, 1, 1, 1, 1]) {
  let acc = 0;
  for (let i = 0; i < KEY.length; i++) {
    const len = KEY[i][0] * scaleTime[i];
    if (t <= acc + len) return KEY[i][1] + (KEY[i][2] - KEY[i][1]) * ease((t - acc) / len);
    acc += len;
  }
  return 0;
}
function phaseAt(t, scaleTime = [1, 1, 1, 1, 1]) {
  const names = ['선 자세', '천천히 내려가기', '바닥 자세 · 잠깐 멈춤', '올라오기', '선 자세'];
  let acc = 0;
  for (let i = 0; i < KEY.length; i++) { acc += KEY[i][0] * scaleTime[i]; if (t < acc) return names[i]; }
  return '선 자세';
}
const totalTime = (sc = [1, 1, 1, 1, 1]) => KEY.reduce((s, k, i) => s + k[0] * sc[i], 0);

/** 기준 사이클 [합성] */
function syntheticReference({ minKnee = 90, trunkMax = 35, dt = 0.1 } = {}) {
  const samples = [];
  for (let t = 0; t <= totalTime() + 1e-9; t += dt) {
    const p = squatShape(depthAt(t), minKnee, trunkMax);
    const J = (pt, x) => [x, pt[1], pt[0]];                 // [x(좌우), y(위), z(앞)]
    samples.push({
      t: Math.round(t * 1000) / 1000, phase: phaseAt(t),
      joints: {
        lShoulder: J(p.shoulder, 0.18), rShoulder: J(p.shoulder, -0.18),
        lHip: J(p.hip, 0.09), rHip: J(p.hip, -0.09),
        lKnee: J(p.knee, 0.09), rKnee: J(p.knee, -0.09),
        lAnkle: J(p.ankle, 0.09), rAnkle: J(p.ankle, -0.09),
      },
    });
  }
  return { exercise: 'squat', lead: null, dt, samples, torso: TORSO, ankleY: 0 };
}

/** 사용자 기록 [합성]: 반복 여러 번, 시간 배율·무릎 최저·결측을 바꿀 수 있다. */
function syntheticRecording({ reps = 3, scaleTime = [1, 1, 1, 1, 1], minKnee = 90, trunkMax = 35, fps = 3,
  restSec = 1.2, dropEvery = 0, gapAt = null, aspect = 0.5625 } = {}) {
  const frames = [];
  let tNow = 0, counted = 0, frameNo = 0;
  const K = 0.55;                                          // 1m → 이미지 높이의 0.55
  const toLm = (shape) => {
    const lm = Array.from({ length: 33 }, () => [0.5, 0.5, 0, 0.99]);
    const put = (idx, pt, dx = 0) => { lm[idx] = [0.5 + (pt[0] * K) / aspect + dx, 0.92 - pt[1] * K, 0, 0.99]; };
    put(11, shape.shoulder, 0.004); put(12, shape.shoulder);
    put(23, shape.hip, 0.004); put(24, shape.hip);
    put(25, shape.knee, 0.004); put(26, shape.knee);
    put(27, shape.ankle, 0.004); put(28, shape.ankle);
    return lm;
  };
  const push = (t, shape, repsNow) => {
    frameNo++;
    let lm = toLm(shape);
    if (dropEvery && frameNo % dropEvery === 0) lm = null;
    if (gapAt && frameNo >= gapAt[0] && frameNo < gapAt[0] + gapAt[1]) lm = null;
    frames.push({ t: Math.round(t * 1000) / 1000, lm, reps: repsNow, lead: null });
  };
  for (let r = 0; r < reps; r++) {
    const dur = totalTime(scaleTime);
    for (let t = 0; t < dur; t += 1 / fps) {
      push(tNow + t, squatShape(depthAt(t, scaleTime), minKnee, trunkMax), counted);
    }
    counted++;
    // 반복이 끝난 프레임(서 있는 자세)에서 횟수가 올라간다
    push(tNow + dur, squatShape(0, minKnee, trunkMax), counted);
    for (let t = 1 / fps; t < restSec; t += 1 / fps) push(tNow + dur + t, squatShape(0, minKnee, trunkMax), counted);
    tNow += dur + restSec;
  }
  return { exercise: 'squat', source: '[합성]', aspect, frames };
}


module.exports = { squatShape, depthAt, phaseAt, totalTime, syntheticReference, syntheticRecording, TORSO };
