/* isOffline · shouldReloadOnReconnect 시험 — 폰·서버 없이 node 로 돈다:  node utils/offline.test.cjs
 * (services/transientFailure.test.cjs 와 같은 방식 — typescript 를 require 훅으로 건다.) */
const assert = require('assert/strict');
require('../lib/motion/register.cjs');
const { isOffline, shouldReloadOnReconnect } = require('./offline');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

console.log('isOffline');
test('연결됨 + 인터넷 됨 → 온라인', () => assert.equal(isOffline({ isConnected: true, isInternetReachable: true }), false));
test('연결 끊김 → 오프라인', () => assert.equal(isOffline({ isConnected: false, isInternetReachable: false }), true));
test('와이파이는 붙었는데 인터넷 안 됨 → 오프라인', () => assert.equal(isOffline({ isConnected: true, isInternetReachable: false }), true));
test('isConnected 만 false → 오프라인', () => assert.equal(isOffline({ isConnected: false, isInternetReachable: null }), true));
test('앱 켠 직후(둘 다 null) → 오프라인 아님 — 배너가 깜빡이면 안 된다', () =>
  assert.equal(isOffline({ isConnected: null, isInternetReachable: null }), false));
test('연결됨 + 도달 가능 여부 모름 → 오프라인 아님', () =>
  assert.equal(isOffline({ isConnected: true, isInternetReachable: null }), false));
test('상태 자체가 없음(null/undefined) → 오프라인 아님', () => {
  assert.equal(isOffline(null), false);
  assert.equal(isOffline(undefined), false);
});

console.log('shouldReloadOnReconnect');
test('오프라인→온라인 + 로딩 실패 → 다시 불러온다', () => assert.equal(shouldReloadOnReconnect(true, false, true), true));
test('오프라인→온라인이지만 정상 화면 → 건드리지 않는다', () => assert.equal(shouldReloadOnReconnect(true, false, false), false));
test('계속 온라인 + 실패 화면 → 자동 재시도 없음(버튼으로만)', () => assert.equal(shouldReloadOnReconnect(false, false, true), false));
test('온라인→오프라인 → 아무것도 안 함', () => assert.equal(shouldReloadOnReconnect(false, true, true), false));
test('계속 오프라인 → 아무것도 안 함', () => assert.equal(shouldReloadOnReconnect(true, true, true), false));

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
