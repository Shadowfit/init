/* reactions 시험 — 폰·서버 없이 node 로 돈다:  node utils/reactions.test.cjs */
const assert = require('assert/strict');
require('../lib/motion/register.cjs');
const { withToggled, normalizeSummary, countOf, hasReacted, emptySummary } = require('./reactions');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

console.log('withToggled — 낙관적 갱신');
test('안 달았으면 +1 하고 내 리액션에 넣는다', () => {
  const s = withToggled({ reactions: { HEART: 2 }, myReactions: [] }, 'HEART');
  assert.equal(countOf(s, 'HEART'), 3);
  assert.equal(hasReacted(s, 'HEART'), true);
});
test('이미 달았으면 -1 하고 내 리액션에서 뺀다', () => {
  const s = withToggled({ reactions: { HEART: 3 }, myReactions: ['HEART'] }, 'HEART');
  assert.equal(countOf(s, 'HEART'), 2);
  assert.equal(hasReacted(s, 'HEART'), false);
});
test('마지막 하나를 취소하면 키를 지운다 — «💗 0» 이 그려지지 않는다', () => {
  const s = withToggled({ reactions: { FIRE: 1 }, myReactions: ['FIRE'] }, 'FIRE');
  assert.deepEqual(s, { reactions: {}, myReactions: [] });
});
test('같은 글의 다른 종류는 건드리지 않는다 (HEART·FIRE 동시 허용)', () => {
  const s = withToggled({ reactions: { HEART: 4, FIRE: 1 }, myReactions: ['HEART'] }, 'FIRE');
  assert.equal(countOf(s, 'HEART'), 4);
  assert.deepEqual(s.myReactions.sort(), ['FIRE', 'HEART']);
});
test('두 번 누르면 원래대로 돌아온다', () => {
  const start = { reactions: { HEART: 2 }, myReactions: [] };
  assert.deepEqual(withToggled(withToggled(start, 'HEART'), 'HEART'), start);
});
test('내 리액션인데 서버 카운트가 0 으로 어긋나 있어도 음수가 되지 않는다', () => {
  const s = withToggled({ reactions: {}, myReactions: ['HEART'] }, 'HEART');
  assert.equal(countOf(s, 'HEART'), 0);
  assert.equal(hasReacted(s, 'HEART'), false);
});
test('원본을 바꾸지 않는다', () => {
  const start = { reactions: { HEART: 1 }, myReactions: [] };
  withToggled(start, 'HEART');
  assert.deepEqual(start, { reactions: { HEART: 1 }, myReactions: [] });
});

console.log('normalizeSummary — 서버 값을 믿지 않는다');
test('undefined·null → 빈 값', () => {
  assert.deepEqual(normalizeSummary(undefined), emptySummary());
  assert.deepEqual(normalizeSummary(null), emptySummary());
});
test('모르는 종류·음수·NaN·문자열 카운트는 버린다', () => {
  const s = normalizeSummary({ reactions: { HEART: -2, FIRE: NaN, LAUGH: 3 }, myReactions: ['LAUGH', 'FIRE', 'FIRE'] });
  assert.deepEqual(s, { reactions: {}, myReactions: ['FIRE'] });
});
test('소수 카운트는 내림', () => {
  assert.equal(countOf(normalizeSummary({ reactions: { HEART: 2.9 }, myReactions: [] }), 'HEART'), 2);
});

console.log(`\n${passed} 통과, ${failed} 실패`);
process.exit(failed ? 1 : 0);
