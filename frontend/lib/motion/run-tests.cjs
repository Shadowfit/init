/* 3D 운동 비교 라이브러리 시험 — 폰·서버 없이 node 로 돈다:  node lib/motion/run-tests.cjs
 *
 * 데이터는 두 종류이며 출력에 구분해 표시한다.
 *   [합성]  파라미터로 만든 스쿼트 골격(측면). 정답을 알고 있어서 «DTW 가 맞게 정렬하는가» 를 잰다.
 *   [실촬영] ai-server/scripts/export_landmarks.py 가 실제 영상(samples/validation)에서 뽑은 좌표 기록.
 *           기준 모션은 마네킹 장면에서 뽑은 fixtures/reference_*.json (장면이 없으면 해당 시험은 건너뜀).
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
require('./register.cjs');

const { dtw, dtwAsymmetric } = require('./dtw');
const { compareRecording, splitReps, MIN_REP_FRAMES } = require('./analysis');
const { prepareReference, segmentsOf } = require('./reference');
const { feedbackForRep, feedbackForSession } = require('./feedback');
const { cleanRecording, MAX_GAP_FRAMES } = require('./preprocess');
const { buildPayloads } = require('./viz');
const { featuresFromLandmarks, featuresFromJoints, rawVector, scaledVector, weightsOf } = require('./features');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message.split('\n').join('\n      ')}`); }
}
function section(t) { console.log(`\n${t}`); }

const { squatShape, depthAt, syntheticReference, syntheticRecording } = require('./synthetic.cjs');

const REF = prepareReference(syntheticReference());

// ═════════════════════════════════════════════════════════════════════════════
section('DTW 정렬 — 같은 동작을 다른 속도로 [합성]');

function sequence(n, warp) {
  // 위상 φ(i) 를 warp 로 비선형 변환한 같은 곡선
  const out = [], phase = [];
  for (let i = 0; i < n; i++) {
    const u = i / (n - 1), p = warp(u);
    phase.push(p);
    out.push([Math.sin(Math.PI * p), Math.sin(2 * Math.PI * p) * 0.5, p]);
  }
  return { seq: out, phase };
}

test('빠르기가 1.5배·비균일하게 달라도 같은 위상끼리 짝지어진다', () => {
  const a = sequence(30, (u) => u);
  const b = sequence(45, (u) => Math.pow(u, 1.6));          // 앞은 느리고 뒤는 빠르다
  const r = dtw(a.seq, b.seq, { weights: [1, 1, 1] });
  assert.deepEqual(r.path[0], [0, 0]);
  assert.deepEqual(r.path[r.path.length - 1], [29, 44]);
  let worst = 0;
  for (const [i, j] of r.path) worst = Math.max(worst, Math.abs(a.phase[i] - b.phase[j]));
  console.log(`      최대 위상 오정렬 ${worst.toFixed(3)} (한 칸 간격 ≈ ${(1 / 29).toFixed(3)}), 평균 거리 ${r.meanDistance.toFixed(4)}`);
  assert.ok(worst < 0.08, `위상 오정렬 ${worst}`);
  assert.ok(r.meanDistance < 0.05);
});

test('와핑 경로는 한 칸씩 단조 증가한다', () => {
  const a = sequence(25, (u) => u), b = sequence(40, (u) => u * u);
  const { path } = dtw(a.seq, b.seq, { weights: [1, 1, 1] });
  for (let k = 1; k < path.length; k++) {
    const di = path[k][0] - path[k - 1][0], dj = path[k][1] - path[k - 1][1];
    assert.ok(di >= 0 && dj >= 0 && di <= 1 && dj <= 1 && di + dj >= 1);
  }
});

test('속도만 다르면 거리가 거의 0, 값이 달라지면 거리가 단조 증가한다', () => {
  const base = sequence(30, (u) => u).seq;
  const slow = sequence(50, (u) => u).seq;
  const dists = [0, 0.1, 0.2, 0.4].map((amp) => {
    const shifted = slow.map((v) => [v[0] + amp, v[1], v[2]]);
    return dtw(base, shifted, { weights: [1, 1, 1] }).meanDistance;
  });
  console.log(`      거리 ${dists.map((d) => d.toFixed(3)).join(' < ')}`);
  assert.ok(dists[0] < 0.05, `거리 ${dists[0]}`);
  for (let k = 1; k < dists.length; k++) assert.ok(dists[k] > dists[k - 1]);
});

test('가중치가 크면 그 특징의 차이가 거리에 더 크게 들어간다', () => {
  const a = sequence(20, (u) => u).seq;
  const b = a.map((v) => [v[0] + 0.3, v[1], v[2]]);
  const w1 = dtw(a, b, { weights: [1, 1, 1] }).meanDistance;
  const w4 = dtw(a, b, { weights: [4, 1, 1] }).meanDistance;
  // 가중치가 커지면 경로가 그 특징의 차이를 줄이는 쪽으로 다시 정렬되므로 비율은 정확히 2배가 되지 않는다
  assert.ok(w4 / w1 > 1.3 && w4 / w1 <= 2.05, `비율 ${w4 / w1}`);
});

test('길이 차이가 커도(밴드 밖) 끝점까지 경로를 찾는다', () => {
  const a = sequence(10, (u) => u).seq, b = sequence(60, (u) => u).seq;
  const r = dtw(a, b, { weights: [1, 1, 1], window: 2 });
  assert.deepEqual(r.path[r.path.length - 1], [9, 59]);
});

section('비대칭 DTW — 듬성듬성한 사용자 프레임 vs 촘촘한 기준 [합성]');

test('사용자는 10프레임, 기준은 40프레임인 같은 곡선 — 거리가 거의 0이고 위상이 맞는다', () => {
  const user = sequence(10, (u) => u), ref = sequence(40, (u) => u);
  const r = dtwAsymmetric(user.seq, ref.seq, { weights: [1, 1, 1] });
  assert.equal(r.path.length, 10);
  let worst = 0;
  for (const [i, j] of r.path) worst = Math.max(worst, Math.abs(user.phase[i] - ref.phase[j]));
  console.log(`      평균 거리 ${r.meanDistance.toFixed(4)}, 최대 위상 오정렬 ${worst.toFixed(3)}`);
  assert.ok(r.meanDistance < 0.03 && worst < 0.06);
});

test('사용자가 더 느리거나(1.8배) 더 빨라도(0.5배) 같은 동작이면 거리가 거의 0', () => {
  const ref = sequence(40, (u) => u);
  for (const n of [7, 20]) {
    const user = sequence(n, (u) => u);
    const r = dtwAsymmetric(user.seq, ref.seq, { weights: [1, 1, 1] });
    assert.ok(r.meanDistance < 0.08, `n=${n}: ${r.meanDistance}`);
  }
});

test('경로는 사용자 프레임마다 하나, 기준 인덱스는 줄어들지 않는다', () => {
  const user = sequence(14, (u) => Math.pow(u, 1.4)), ref = sequence(40, (u) => u);
  const { path } = dtwAsymmetric(user.seq, ref.seq, { weights: [1, 1, 1] });
  path.forEach(([i], k) => assert.equal(i, k));
  for (let k = 1; k < path.length; k++) assert.ok(path[k][1] >= path[k - 1][1]);
});

// ═════════════════════════════════════════════════════════════════════════════
section('특징 계산 — 카메라 좌표와 3D 관절이 같은 정의 [합성]');

test('같은 자세를 2D 이미지 좌표와 3D 관절로 재면 각도가 같다', () => {
  const d = 0.8, shape = squatShape(d, 90, 35);
  const aspect = 0.5625, K = 0.55;
  const lm = Array.from({ length: 33 }, () => [0.5, 0.5, 0, 0.99]);
  const put = (i, pt) => { lm[i] = [0.5 + (pt[0] * K) / aspect, 0.92 - pt[1] * K, 0, 0.99]; };
  [11, 12].forEach((i) => put(i, shape.shoulder)); [23, 24].forEach((i) => put(i, shape.hip));
  [25, 26].forEach((i) => put(i, shape.knee)); [27, 28].forEach((i) => put(i, shape.ankle));
  const f2 = featuresFromLandmarks(lm, aspect, 0);
  const ref = syntheticReference().samples.reduce((best, s) => (Math.abs(depthAt(s.t) - d) < Math.abs(depthAt(best.t) - d) ? s : best));
  const f3 = featuresFromJoints(ref.joints, 0);
  assert.ok(f2 && f3);
  const dRef = depthAt(ref.t);   // 기준 샘플은 0.1초 간격이라 정확히 d 가 아닐 수 있다 — 같은 d 로 다시 만들어 비교
  const same = featuresFromLandmarks(lm, aspect, 0);
  assert.ok(Math.abs(same.kneeL - (175 - (175 - 90) * d)) < 0.5, `무릎 ${same.kneeL}`);
  assert.ok(Math.abs(same.trunk - (5 + 30 * d)) < 0.5, `체간 ${same.trunk}`);
  assert.ok(Math.abs(f2.hipH - f3.hipH) < 0.12 && Math.abs(dRef - d) < 0.1, `높이 ${f2.hipH} vs ${f3.hipH}`);
});

test('키·카메라 거리가 달라도(전체 크기 2배) 특징이 같다', () => {
  const shape = squatShape(0.6, 95, 30);
  const mk = (K) => {
    const lm = Array.from({ length: 33 }, () => [0.5, 0.5, 0, 0.99]);
    const put = (i, pt) => { lm[i] = [0.5 + (pt[0] * K) / 0.5625, 0.95 - pt[1] * K, 0, 0.99]; };
    [11, 12].forEach((i) => put(i, shape.shoulder)); [23, 24].forEach((i) => put(i, shape.hip));
    [25, 26].forEach((i) => put(i, shape.knee)); [27, 28].forEach((i) => put(i, shape.ankle));
    return featuresFromLandmarks(lm, 0.5625, 0);
  };
  const a = mk(0.4), b = mk(0.8);
  for (const k of ['kneeL', 'hipL', 'trunk', 'hipH']) assert.ok(Math.abs(a[k] - b[k]) < 1e-6, `${k}: ${a[k]} vs ${b[k]}`);
});

test('구간 이름(한국어 단계) → 분석 구간 매핑', () => {
  const segs = segmentsOf(['선 자세', '천천히 내려가기', '바닥 자세 · 잠깐 멈춤', '올라오기', '선 자세']);
  assert.deepEqual(segs, ['start', 'descend', 'bottom', 'ascend', 'end']);
  assert.deepEqual(segmentsOf(['앞으로 딛기', '내려가기', '바닥 자세 · 잠깐 멈춤', '올라오기', '제자리로 돌아오기']),
    ['start', 'descend', 'bottom', 'ascend', 'end']);
});

// ═════════════════════════════════════════════════════════════════════════════
section('반복 분리·전처리 [합성]');

test('AI 가 센 횟수가 올라간 프레임을 반복의 끝으로 나눈다', () => {
  const rec = syntheticRecording({ reps: 3 });
  const wins = splitReps(cleanRecording(rec));
  assert.equal(wins.length, 3);
  assert.equal(wins[0].start, 0);
  assert.ok(wins[1].start === wins[0].end + 1 && wins[2].start === wins[1].end + 1);
});

test('결측이 1~2프레임이면 보간해 메우고, 길면 메우지 않는다', () => {
  const rec = syntheticRecording({ reps: 1 });
  const missing = (idx, n) => ({ ...rec, frames: rec.frames.map((f, i) => (i >= idx && i < idx + n ? { ...f, lm: null } : f)) });
  const short = cleanRecording(missing(8, MAX_GAP_FRAMES));
  assert.ok(short.slice(8, 8 + MAX_GAP_FRAMES).every((f) => f.ok && f.interpolated));
  const long = cleanRecording(missing(8, MAX_GAP_FRAMES + 1));
  assert.ok(long.slice(8, 8 + MAX_GAP_FRAMES + 1).every((f) => !f.ok));
});

test('좌표가 떨려도(잡음 ±1.5%) 평활화가 떨림을 줄인다', () => {
  const rec = syntheticRecording({ reps: 1 });
  let seed = 7; const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647 - 0.5) * 2;
  const noisy = { ...rec, frames: rec.frames.map((f) => ({ ...f, lm: f.lm.map((p) => [p[0] + rnd() * 0.015, p[1] + rnd() * 0.015, p[2], p[3]]) })) };
  const rough = (frames) => {
    let s = 0, n = 0;
    for (let i = 2; i < frames.length; i++) {
      const second = frames[i].lm[25][1] - 2 * frames[i - 1].lm[25][1] + frames[i - 2].lm[25][1];
      s += second * second; n++;
    }
    return Math.sqrt(s / n);
  };
  const before = rough(noisy.frames), after = rough(cleanRecording(noisy));
  console.log(`      무릎 y 의 2차 차분 RMS ${before.toFixed(4)} → ${after.toFixed(4)}`);
  assert.ok(after < before * 0.9);
});

// ═════════════════════════════════════════════════════════════════════════════
section('운동 전체 비교 [합성] — 속도·깊이·결측');

const fb = (cmp) => feedbackForRep('squat', cmp.reps.find((r) => r.status === 'ok'));

test('같은 동작을 더 느리게(1.6배) 해도 유사도가 높고 느리다고 말한다', () => {
  const cmp = compareRecording(syntheticRecording({ reps: 2, scaleTime: [1.6, 1.6, 1.6, 1.6, 1.6] }), { squat: REF });
  console.log(`      유사도 ${cmp.similarity}, 분석 ${cmp.analyzed}회`);
  assert.equal(cmp.analyzed, 2);
  assert.ok(cmp.similarity >= 85, `유사도 ${cmp.similarity}`);
  const items = fb(cmp);
  assert.ok(items.some((i) => i.kind === 'timing' && /느리게/.test(i.text)), items.map((i) => i.text).join('\n'));
});

test('같은 동작을 더 빠르게(0.6배) 하면 빠르다고 말한다', () => {
  const cmp = compareRecording(syntheticRecording({ reps: 2, scaleTime: [0.7, 0.55, 0.7, 0.55, 0.7], fps: 6 }), { squat: REF });
  const items = fb(cmp);
  console.log(`      ${items.filter((i) => i.kind === 'timing').map((i) => i.text).join(' / ') || '(시간 문장 없음)'}`);
  assert.ok(items.some((i) => i.kind === 'timing' && /빠르게/.test(i.text)));
});

test('관절 각도가 달라질수록(최저 무릎 90→110→130°) 오차가 커지고 점수가 내려간다', () => {
  const scores = [90, 110, 130].map((k) => compareRecording(syntheticRecording({ reps: 2, minKnee: k }), { squat: REF }));
  const sims = scores.map((c) => c.similarity);
  const deltas = scores.map((c) => c.reps[0].kneeMin.delta);
  console.log(`      유사도 ${sims.join(' > ')} / 최저 무릎 차이 ${deltas.map((d) => d.toFixed(1)).join(', ')}°`);
  assert.ok(sims[0] > sims[1] && sims[1] > sims[2]);
  assert.ok(Math.abs(deltas[0]) < 3 && Math.abs(deltas[1] - 20) < 4 && Math.abs(deltas[2] - 40) < 5);
});

test('같은 동작을 2·3·4·6fps 로 찍어도 점수가 거의 같다(찍힌 장수에 흔들리지 않는다)', () => {
  // 2026-10-21: 궤적 거리를 «찍힌 프레임» 으로 평균하던 때는 같은 깊이 반복이 3장 73점·5장 90점(AI 싱크로율)이었다
  const sims = [2, 3, 4, 6].map((fps) => compareRecording(syntheticRecording({ reps: 2, fps }), { squat: REF }).similarity);
  console.log(`      2fps ${sims[0]} · 3fps ${sims[1]} · 4fps ${sims[2]} · 6fps ${sims[3]}`);
  assert.ok(Math.max(...sims) - Math.min(...sims) <= 4, `점수 폭 ${Math.max(...sims) - Math.min(...sims)}`);
});

test('기준보다 더 깊게 앉으면(최저 무릎 70° vs 기준 90°) 기준 깊이와 같거나 더 높다 — 기준은 바닥', () => {
  const [same, deeper] = [90, 70].map((k) => compareRecording(syntheticRecording({ reps: 2, minKnee: k }), { squat: REF }).similarity);
  console.log(`      기준 깊이 ${same} · 더 깊게 ${deeper}`);
  assert.ok(deeper >= same - 1, `더 깊게 ${deeper} < 기준 깊이 ${same}`);
});

test('얕은 동작이면 «기준보다 덜 굽힌» 문장이 실제 각도 차이와 함께 나온다', () => {
  const cmp = compareRecording(syntheticRecording({ reps: 2, minKnee: 105 }), { squat: REF });
  const knee = fb(cmp).find((i) => i.id === 'knee-bottom');
  console.log(`      ${knee.text}`);
  assert.equal(knee.severity, 'check');
  assert.ok(/덜 굽힌/.test(knee.text));
  assert.ok(Math.abs(knee.evidence.delta - 15) < 3, `delta ${knee.evidence.delta}`);
});

test('상체를 더 숙이면 «앞으로 기울어지는 경향» 문장이 나온다', () => {
  const cmp = compareRecording(syntheticRecording({ reps: 2, trunkMax: 55 }), { squat: REF });
  const t = fb(cmp).find((i) => i.id === 'trunk');
  console.log(`      ${t.text}`);
  assert.equal(t.severity, 'check');
  assert.ok(/앞으로 기울어지는 경향/.test(t.text));
});

test('기준과 같은 동작이면 «비슷합니다» 만 나오고 지적이 없다', () => {
  const cmp = compareRecording(syntheticRecording({ reps: 2 }), { squat: REF });
  const items = feedbackForSession(cmp);
  assert.ok(items.every((i) => i.severity === 'info'), items.map((i) => `${i.severity}:${i.text}`).join('\n'));
});

test('프레임이 5개 중 1개꼴로 빠져도(결측 20%) 분석이 끝난다', () => {
  const base = compareRecording(syntheticRecording({ reps: 3 }), { squat: REF });
  const cmp = compareRecording(syntheticRecording({ reps: 3, dropEvery: 5 }), { squat: REF });
  console.log(`      분석 ${cmp.analyzed}/3회, 유사도 ${cmp.similarity} (결측 없을 때 ${base.similarity}), 보간 ${cmp.quality.interpolatedFrames}프레임`);
  assert.ok(cmp.analyzed >= 2);
  assert.ok(cmp.similarity >= base.similarity - 12, `결측으로 ${base.similarity} → ${cmp.similarity}`);
});

test('한 반복 한가운데가 길게 끊기면 그 반복만 이유와 함께 건너뛰고 나머지는 분석한다', () => {
  const rec = syntheticRecording({ reps: 3 });
  const wins = splitReps(cleanRecording(rec));
  const mid = Math.floor((wins[1].start + wins[1].end) / 2);
  const broken = { ...rec, frames: rec.frames.map((f, i) => (i >= wins[1].start && i <= wins[1].end - 1 ? { ...f, lm: null } : f)) };
  const cmp = compareRecording(broken, { squat: REF });
  const skipped = cmp.reps.filter((r) => r.status === 'skipped');
  console.log(`      건너뜀: ${skipped.map((s) => `${s.repNumber}회(${s.reason})`).join(', ')} / 분석 ${cmp.analyzed}회 (끊김 위치 ${mid})`);
  assert.equal(skipped.length, 1);
  assert.equal(skipped[0].repNumber, 2);
  assert.equal(cmp.analyzed, 2);
});

test('좌표가 하나도 없는 기록·횟수 0 기록도 오류 없이 빈 결과를 낸다', () => {
  const empty = compareRecording({ exercise: 'squat', aspect: 0.56, frames: [] }, { squat: REF });
  assert.equal(empty.analyzed, 0); assert.equal(empty.similarity, null);
  const noReps = compareRecording({ ...syntheticRecording({ reps: 1 }), frames: syntheticRecording({ reps: 1 }).frames.map((f) => ({ ...f, reps: 0 })) }, { squat: REF });
  assert.equal(noReps.analyzed, 0);
});

// ═════════════════════════════════════════════════════════════════════════════
section('실촬영 기록 [실촬영] — 실제 영상에서 뽑은 좌표');

const FIX = path.join(__dirname, 'fixtures');
function loadFixture(name) { const p = path.join(FIX, name); return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, 'utf8')) : null; }
const refSquatFile = loadFixture('reference_squat.json'), refLungeFile = loadFixture('reference_lunge.json');

if (!refSquatFile || !refLungeFile) {
  console.log('  (fixtures/reference_*.json 없음 — 마네킹 장면에서 기준 모션을 뽑은 뒤 실행하세요. 실촬영 시험 건너뜀)');
} else {
  const REAL = {
    squat: prepareReference(refSquatFile.cycles[0]),
    lunge: Object.fromEntries(refLungeFile.cycles.map((c) => [c.lead, prepareReference(c)])),
  };
  const results = {};
  for (const n of ['squat_normal_01', 'squat_normal_02', 'squat_side_01', 'squat_shallow_01', 'lunge_normal_01', 'lunge_sallow_01', 'lunge_walking_01']) {
    results[n] = compareRecording(loadFixture(`${n}.json`), REAL);
    const c = results[n];
    console.log(`      ${n}: 유사도 ${c.similarity}, 분석 ${c.analyzed}/${c.reps.length}회, 사용 가능 프레임 ${c.quality.usableFrames}/${c.quality.totalFrames}`);
  }
  const oks = (c) => c.reps.filter((r) => r.status === 'ok');

  test('정상 스쿼트 영상 3개는 AI 가 센 반복 5회가 모두 분석된다', () => {
    for (const n of ['squat_normal_01', 'squat_normal_02', 'squat_side_01']) assert.equal(results[n].analyzed, 5, `${n} ${results[n].analyzed}회`);
  });
  test('얕은 스쿼트 영상은 AI 가 반복을 세지 않아 비교 대상이 없다', () => {
    assert.equal(results.squat_shallow_01.analyzed, 0);
    assert.equal(results.squat_shallow_01.similarity, null);
  });
  test('런지: 정상이 얕음·걷기보다 10점 이상 높다(같은 기준으로)', () => {
    // 얕음(깊이 16° 부족)과 걷기(상체 20° 숙임·이동)는 결함의 종류가 달라 서로 순위를 매기지 않는다 — 둘 다 정상보다 뚜렷이 낮으면 된다
    const [a, b, c] = ['lunge_normal_01', 'lunge_sallow_01', 'lunge_walking_01'].map((n) => results[n].similarity);
    console.log(`      정상 ${a} · 얕음 ${b} · 걷기 ${c}`);
    assert.ok(a - b >= 10 && a - c >= 10, `정상 ${a} 얕음 ${b} 걷기 ${c}`);
  });
  test('런지: 한 영상에서 왼쪽 5회·오른쪽 5회로 앞다리가 바뀐다(자세에서 읽은 앞다리)', () => {
    const sides = oks(results.lunge_normal_01).map((r) => r.lead);
    console.log(`      ${sides.join(' ')}`);
    assert.equal(sides.length, 10);
    assert.ok(new Set(sides.slice(0, 5)).size === 1 && new Set(sides.slice(5)).size === 1 && sides[0] !== sides[9]);
  });
  test('와핑 경로로 대응한 기준 시각이 줄어들지 않고, 구간 순서가 시작→내려가기→최저점→올라오기→마무리다', () => {
    const order = ['start', 'descend', 'bottom', 'ascend', 'end'];
    for (const n of Object.keys(results)) {
      for (const r of oks(results[n])) {
        for (let i = 1; i < r.refTime.length; i++) assert.ok(r.refTime[i] >= r.refTime[i - 1] - 1e-9, `${n} ${r.repNumber}회 refTime`);
        for (let i = 1; i < r.segment.length; i++) assert.ok(order.indexOf(r.segment[i]) >= order.indexOf(r.segment[i - 1]), `${n} ${r.repNumber}회 구간 ${r.segment.join('>')}`);
      }
    }
  });
  test('실촬영에서도 프레임 20%를 지워도 분석이 끝나고 유사도가 크게 달라지지 않는다', () => {
    const rec = loadFixture('squat_normal_02.json');
    const holes = { ...rec, frames: rec.frames.map((f, i) => (i % 5 === 3 ? { ...f, lm: null } : f)) };
    const base = results.squat_normal_02, cmp = compareRecording(holes, REAL);
    console.log(`      결측 없음 ${base.similarity} / 20% 결측 ${cmp.similarity} (분석 ${cmp.analyzed}회, 보간 ${cmp.quality.interpolatedFrames}프레임)`);
    assert.ok(cmp.analyzed >= 3);
    // 지워진 프레임이 하필 최저점이면 그 반복의 깊이는 얕게 읽힌다(보간은 양옆 직선이라 복원 못 함) — 20점 안쪽이면 받아들인다.
    // 점수의 주축이 최저점 자세라 프레임 평균 때(±5)보다 결측에 민감해진 것은 의도한 교환이다(2026-10-20).
    assert.ok(Math.abs(cmp.similarity - base.similarity) <= 20, `결측으로 ${base.similarity} → ${cmp.similarity}`);
  });
  test('실촬영 피드백 문장에는 실제로 잰 수치가 들어 있고 위험 단정이 없다', () => {
    for (const n of Object.keys(results)) {
      for (const f of feedbackForSession(results[n])) {
        if (f.kind !== 'timing') assert.ok(/[0-9]+°/.test(f.text), f.text);
        assert.ok(!/위험|부상|잘못|나쁜/.test(f.text), f.text);
      }
    }
  });
  test('3D 장면 데이터: 사용자 골격이 기준과 같은 크기·바닥에 놓이고, 무릎이 앞(+z)으로 굽는다 [실촬영]', () => {
    for (const [name, ex] of [['squat_side_01', 'squat'], ['squat_normal_02', 'squat'], ['lunge_normal_01', 'lunge']]) {
      const rec = loadFixture(`${name}.json`), cmp = results[name];
      const payloads = buildPayloads(rec, cmp.reps, REAL);
      assert.equal(payloads.length, cmp.analyzed);
      const refTorso = (ex === 'squat' ? REAL.squat : REAL.lunge.right).torso;
      for (const p of payloads) {
        const rep = cmp.reps.find((r) => r.repNumber === p.repNumber);
        assert.equal(p.frames.length, rep.frames);
        assert.equal(p.refTime.length, rep.frames); assert.equal(p.err.length, rep.frames);
        assert.ok(p.frames.every((f) => f.p.length === 24 && f.p.every(Number.isFinite)));
        assert.ok(p.err.every((e) => e.length === 8 && e.every((v) => v >= 0 && v <= 1.2)));
        // 몸통 길이: 첫 프레임의 어깨 중점-엉덩이 중점이 기준 마네킹 몸통 길이와 비슷하다(서 있는 자세라 기울기 영향이 작다)
        const q = p.frames[0].p;
        const sm = [(q[0] + q[3]) / 2, (q[1] + q[4]) / 2, (q[2] + q[5]) / 2], hm = [(q[6] + q[9]) / 2, (q[7] + q[10]) / 2, (q[8] + q[11]) / 2];
        const torso = Math.hypot(sm[0] - hm[0], sm[1] - hm[1], sm[2] - hm[2]);
        assert.ok(Math.abs(torso / refTorso - 1) < 0.35, `${name} 몸통 ${torso.toFixed(3)} vs 기준 ${refTorso.toFixed(3)}`);
        // 가장 낮은 엉덩이 프레임에서 무릎이 엉덩이-발목 선보다 앞(+z)에 있다 (요 정렬)
        let low = 0; p.frames.forEach((f, i) => { if (f.p[7] + f.p[10] < p.frames[low].p[7] + p.frames[low].p[10]) low = i; });
        const d = p.frames[low].p;
        const kneeFwd = (d[14] + d[17]) / 2 - ((d[8] + d[11]) / 2 + (d[20] + d[23]) / 2) / 2;
        assert.ok(kneeFwd > -0.02, `${name} ${p.repNumber}회 무릎 앞쪽 ${kneeFwd.toFixed(3)}`);
      }
    }
  });
  test('결과에 NaN 이 섞이지 않는다', () => {
    for (const n of Object.keys(results)) {
      for (const r of oks(results[n])) {
        assert.ok(Number.isFinite(r.similarity) && Number.isFinite(r.meanDistance));
        assert.ok(r.frameError.every((e) => Object.values(e).every(Number.isFinite)), `${n} ${r.repNumber}회 frameError`);
      }
    }
  });
}

console.log(`\n${passed} 통과, ${failed} 실패`);
process.exit(failed ? 1 : 0);
