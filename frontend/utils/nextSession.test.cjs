/* nextSession 표시 문구 시험 — 폰·서버 없이 node 로 돈다:  node utils/nextSession.test.cjs */
const assert = require('assert/strict');
require('../lib/motion/register.cjs');
const { formatRest, formatPercent, toNextSessionView } = require('./nextSession');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

console.log('formatRest');
test('90초 → 1분 30초', () => assert.equal(formatRest(90), '1분 30초'));
test('60초 → 1분 (0초를 붙이지 않는다)', () => assert.equal(formatRest(60), '1분'));
test('45초 → 45초', () => assert.equal(formatRest(45), '45초'));
test('0초 → 없음', () => assert.equal(formatRest(0), '없음'));
test('음수·NaN·null·문자열 → «-» (그리지 못할 값)', () => {
  for (const bad of [-5, NaN, Infinity, null, undefined, '90']) assert.equal(formatRest(bad), '-');
});

console.log('formatPercent');
test('80 → 80%', () => assert.equal(formatPercent(80), '80%'));
test('82.5 → 82.5%', () => assert.equal(formatPercent(82.5), '82.5%'));
test('82.04 → 82%, 부동소수 잡음 제거(0.1+0.2 계열)', () => {
  assert.equal(formatPercent(82.04), '82%');
  assert.equal(formatPercent(80.30000000000001), '80.3%');
});
test('문자열·null → «-»', () => {
  assert.equal(formatPercent('80'), '-');
  assert.equal(formatPercent(null), '-');
});

console.log('toNextSessionView');
test('정상 응답', () => {
  const v = toNextSessionView({
    difficultyLevel: 4, targetReps: 12, targetSyncRate: 82.5, restTimeSec: 75,
    reason: '  최근 3세션 평균 싱크로율 88.0%로 안정적 → 난이도를 상향합니다.  ',
  });
  assert.deepEqual(v, {
    level: 'Lv.4', reps: '12회', sync: '82.5%', rest: '1분 15초',
    reason: '최근 3세션 평균 싱크로율 88.0%로 안정적 → 난이도를 상향합니다.',
  });
});
test('빈 응답·null 이어도 던지지 않고 «-» 로 채운다', () => {
  for (const empty of [{}, null, undefined]) {
    const v = toNextSessionView(empty);
    assert.deepEqual(v, { level: '-', reps: '-', sync: '-', rest: '-', reason: '' });
  }
});
test('reason 이 문자열이 아니면 빈 문자열 — «undefined» 가 화면에 찍히지 않는다', () => {
  assert.equal(toNextSessionView({ reason: undefined }).reason, '');
  assert.equal(toNextSessionView({ reason: 123 }).reason, '');
});

console.log(`\n${passed} 통과, ${failed} 실패`);
process.exit(failed ? 1 : 0);
