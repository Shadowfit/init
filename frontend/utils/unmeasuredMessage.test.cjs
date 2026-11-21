/* unmeasuredMessage 시험 — 폰·서버 없이 node 로 돈다:  node utils/unmeasuredMessage.test.cjs */
const assert = require('assert/strict');
require('../lib/motion/register.cjs');
const { unmeasuredMessage } = require('./unmeasuredMessage');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

const GENERIC = unmeasuredMessage(undefined);

console.log('unmeasuredMessage');
test('NO_POSE 는 «사람을 찾지 못했다» 안내', () => assert.match(unmeasuredMessage('NO_POSE'), /사람을 찾지 못했어요/));
test('NOT_FULLY_VISIBLE 는 «전신이 들어오게» 안내', () => assert.match(unmeasuredMessage('NOT_FULLY_VISIBLE'), /화면 밖.*전신/));
test('NO_REP 는 «동작을 끝까지» 안내', () => assert.match(unmeasuredMessage('NO_REP'), /인정된 반복이 없었어요/));
test('세 사유의 문구가 서로 다르다(같은 말을 세 번 하지 않는다)', () => {
  const m = new Set(['NO_POSE', 'NOT_FULLY_VISIBLE', 'NO_REP'].map(unmeasuredMessage));
  assert.equal(m.size, 3);
});
test('사유가 없으면 예전 일반 안내', () => {
  assert.match(GENERIC, /측정된 반복이 없어요/);
  assert.equal(unmeasuredMessage(null), GENERIC);
  assert.equal(unmeasuredMessage(''), GENERIC);
});
test('모르는 새 값(신버전 서버)도 일반 안내로 떨어진다 — 던지지 않는다', () => {
  assert.equal(unmeasuredMessage('SOMETHING_NEW'), GENERIC);
});
test('일반 안내와 세 사유 안내는 서로 다르다', () => {
  for (const s of ['NO_POSE', 'NOT_FULLY_VISIBLE', 'NO_REP']) assert.notEqual(unmeasuredMessage(s), GENERIC);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
