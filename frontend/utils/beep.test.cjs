/* 비프음 로직 시험 — 폰·서버 없이 node 로 돈다:  node utils/beep.test.cjs
 *  · 어떤 이벤트가 어떤 소리를 내는가 / 쿨다운·최소 간격·끄기 / 막힌 요청이 쿨다운을 연장하지 않는가
 *  · 생성된 WAV 가 규격대로이고 클리핑·클릭이 없는가 (저장소에 커밋된 파일 그대로를 검사) */
const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
require('../lib/motion/register.cjs');
const {
  BEEP_COOLDOWN_MS, MIN_GAP_MS, createBeepGate, decideBeep, beepKindForResponse, shouldNoticeOnReadiness,
} = require('./beep');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

console.log('beepKindForResponse — 응답 → 소리 종류');
test('rep 완료 안내 → success', () => assert.equal(beepKindForResponse({ message: '좋아요!', rep_completed: true }), 'success'));
test('rep 미인정 안내(message, 완료 아님) → warning', () => assert.equal(beepKindForResponse({ message: '깊이가 부족해요', rep_completed: false }), 'warning'));
test('rep_completed 가 없는 message → warning(완료로 단정하지 않는다)', () => assert.equal(beepKindForResponse({ message: '안내' }), 'warning'));
test('프레임 단위 자세 결함만 → warning', () => assert.equal(beepKindForResponse({ feedback_type: 'KNEE_IN' }), 'warning'));
test('message 와 feedback_type 이 같이 오면 message(rep 단위)가 우선', () =>
  assert.equal(beepKindForResponse({ message: 'ok', rep_completed: true, feedback_type: 'KNEE_IN' }), 'success'));
test('아무 안내도 없으면 소리 없음', () => {
  assert.equal(beepKindForResponse({}), null);
  assert.equal(beepKindForResponse({ message: '', feedback_type: null }), null);
});

console.log('decideBeep — 쿨다운·최소 간격·끄기');
test('처음에는 울린다', () => assert.equal(decideBeep(createBeepGate(), 'warning', 10_000, true).play, true));
test('꺼져 있으면(TTS 토글 off) 울리지 않고 상태도 안 바뀐다', () => {
  const g = createBeepGate();
  const d = decideBeep(g, 'success', 10_000, false);
  assert.equal(d.play, false);
  assert.equal(d.gate, g);
});
test('warning 은 2.5초 안에 또 울리지 않는다', () => {
  let g = decideBeep(createBeepGate(), 'warning', 10_000, true).gate;
  assert.equal(decideBeep(g, 'warning', 10_000 + BEEP_COOLDOWN_MS.warning - 1, true).play, false);
  assert.equal(decideBeep(g, 'warning', 10_000 + BEEP_COOLDOWN_MS.warning, true).play, true);
});
test('success 는 쿨다운이 없다(최소 간격만)', () => {
  let g = decideBeep(createBeepGate(), 'success', 10_000, true).gate;
  assert.equal(decideBeep(g, 'success', 10_000 + MIN_GAP_MS, true).play, true);
});
test('종류가 달라도 직전 소리 뒤 최소 간격 안이면 막는다(소리가 겹쳐 뭉개지지 않게)', () => {
  let g = decideBeep(createBeepGate(), 'warning', 10_000, true).gate;
  assert.equal(decideBeep(g, 'success', 10_000 + MIN_GAP_MS - 1, true).play, false);
  assert.equal(decideBeep(g, 'success', 10_000 + MIN_GAP_MS, true).play, true);
});
test('막힌 요청은 쿨다운을 연장하지 않는다 — 계속 두드려도 쿨다운이 끝나면 울린다', () => {
  let g = decideBeep(createBeepGate(), 'warning', 0, true).gate;
  for (let t = 500; t < 2500; t += 500) {
    const d = decideBeep(g, 'warning', t, true);
    assert.equal(d.play, false);
    g = d.gate; // 막힌 요청의 상태는 그대로여야 한다
  }
  assert.equal(decideBeep(g, 'warning', 2500, true).play, true);
});
test('다른 종류의 쿨다운은 서로 독립이다(warning 직후에도 notice 는 최소 간격만 넘으면 된다)', () => {
  let g = decideBeep(createBeepGate(), 'warning', 0, true).gate;
  assert.equal(decideBeep(g, 'notice', MIN_GAP_MS, true).play, true);
});
test('입력 상태를 변경하지 않는다(불변)', () => {
  const g = createBeepGate();
  const snapshot = JSON.stringify(g);
  decideBeep(g, 'warning', 1, true);
  assert.equal(JSON.stringify(g), snapshot);
});

console.log('shouldNoticeOnReadiness — «전신 안 보임» 전이');
test('운동 중 정상→warn 으로 막 바뀌면 알린다', () => assert.equal(shouldNoticeOnReadiness('active', 'warn', true), true));
test('처음 상태(null)에서 warn 이어도 알린다', () => assert.equal(shouldNoticeOnReadiness(null, 'warn', true), true));
test('이미 warn 인 채 이어지면 알리지 않는다', () => assert.equal(shouldNoticeOnReadiness('warn', 'warn', true), false));
test('운동 중이 아니면(준비 화면) 알리지 않는다', () => assert.equal(shouldNoticeOnReadiness('idle', 'warn', false), false));
test('연결 오류(error)는 소리를 내지 않는다', () => assert.equal(shouldNoticeOnReadiness('active', 'error', true), false));
test('warn → 정상 → warn 이면 다시 알린다', () => {
  assert.equal(shouldNoticeOnReadiness('warn', 'active', true), false);
  assert.equal(shouldNoticeOnReadiness('active', 'warn', true), true);
});

console.log('WAV 파일 — 저장소에 커밋된 그대로');
const SOUND_DIR = path.join(__dirname, '..', 'assets', 'sounds');
const EXPECT = { 'beep-success.wav': [250, 270], 'beep-warning.wav': [230, 250], 'beep-notice.wav': [210, 230] };
for (const [name, [minMs, maxMs]] of Object.entries(EXPECT)) {
  const buf = fs.readFileSync(path.join(SOUND_DIR, name));
  const n = (buf.length - 44) / 2;
  const samples = Array.from({ length: n }, (_, i) => buf.readInt16LE(44 + i * 2));
  test(`${name}: 16bit PCM 모노 44.1kHz, 길이 ${minMs}~${maxMs}ms`, () => {
    assert.equal(buf.toString('ascii', 0, 4), 'RIFF');
    assert.equal(buf.toString('ascii', 8, 12), 'WAVE');
    assert.equal(buf.readUInt16LE(20), 1);       // PCM
    assert.equal(buf.readUInt16LE(22), 1);       // 모노
    assert.equal(buf.readUInt32LE(24), 44100);
    assert.equal(buf.readUInt16LE(34), 16);
    assert.equal(buf.readUInt32LE(40), n * 2);   // data 크기와 실제 일치
    assert.equal(buf.readUInt32LE(4), 36 + n * 2);
    const ms = (n / 44100) * 1000;
    assert.ok(ms >= minMs && ms <= maxMs, `길이 ${ms.toFixed(0)}ms`);
  });
  test(`${name}: 클리핑 없음(최대 진폭 < 0.6) · 소리가 실제로 있음(최대 > 0.3) · 시작·끝이 0 에 가깝다(클릭 방지)`, () => {
    const peak = Math.max(...samples.map(Math.abs)) / 32767;
    assert.ok(peak < 0.6, `peak ${peak.toFixed(3)}`);
    assert.ok(peak > 0.3, `peak ${peak.toFixed(3)}`);
    assert.ok(Math.abs(samples[0]) < 300 && Math.abs(samples[n - 1]) < 300, `양끝 ${samples[0]}, ${samples[n - 1]}`);
  });
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
