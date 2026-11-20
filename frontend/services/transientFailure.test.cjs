/* isTransientFailure 시험 — 폰·서버 없이 node 로 돈다:  node services/transientFailure.test.cjs
 * (lib/motion/run-tests.cjs 와 같은 방식 — typescript 를 require 훅으로 건다. node_modules 가 필요하다) */
const assert = require('assert/strict');
require('../lib/motion/register.cjs');
const { isTransientFailure } = require('./transientFailure');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

console.log('isTransientFailure');
test('503 → 일시적', () => assert.equal(isTransientFailure({ response: { status: 503 } }), true));
test('500 → 일시적', () => assert.equal(isTransientFailure({ response: { status: 500 } }), true));
test('401(재발급 거절) → 일시적이 아니다 — 세션 종료', () => assert.equal(isTransientFailure({ response: { status: 401 } }), false));
test('400 → 아니다', () => assert.equal(isTransientFailure({ response: { status: 400 } }), false));
test('404 → 아니다', () => assert.equal(isTransientFailure({ response: { status: 404 } }), false));
test('응답 없이 요청만 나감(네트워크 단절) → 일시적', () => assert.equal(isTransientFailure({ request: {} }), true));
test('타임아웃 ECONNABORTED → 일시적', () => assert.equal(isTransientFailure({ code: 'ECONNABORTED' }), true));
test('ERR_NETWORK → 일시적', () => assert.equal(isTransientFailure({ code: 'ERR_NETWORK' }), true));
test('요청 전에 난 오류(설정 실수) → 아니다', () => assert.equal(isTransientFailure(new Error('bad config')), false));
test('null·undefined → 아니다', () => {
  assert.equal(isTransientFailure(null), false);
  assert.equal(isTransientFailure(undefined), false);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
