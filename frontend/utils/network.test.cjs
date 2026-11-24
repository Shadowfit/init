/* useReloadOnReconnect 훅 시험 — 연결이 끊겼다 돌아오는 전이를 react-test-renderer 로 실제로 돌린다:
 *   node utils/network.test.cjs
 * netinfo 는 네이티브 모듈이라 require 훅으로 바꿔치기한다(상태를 이 파일이 쥐고 흔든다). */
const assert = require('assert/strict');
const Module = require('module');
require('../lib/motion/register.cjs');

let netState = { isConnected: true, isInternetReachable: true };
const origLoad = Module._load;
Module._load = function (request, ...rest) {
  if (request === '@react-native-community/netinfo') return { useNetInfo: () => netState };
  if (request === '@/utils/offline') return origLoad.call(this, require('path').join(__dirname, 'offline'), ...rest);
  return origLoad.call(this, request, ...rest);
};

const React = require('react');
const TestRenderer = require('react-test-renderer');
const { act } = TestRenderer;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const { useReloadOnReconnect } = require('./network');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

function setup(initialFailed) {
  netState = { isConnected: true, isInternetReachable: true };
  const calls = { n: 0 };
  let props = { failed: initialFailed, reload: () => { calls.n++; } };
  function Probe(p) { useReloadOnReconnect(p.failed, p.reload); return null; }
  let r;
  act(() => { r = TestRenderer.create(React.createElement(Probe, props)); });
  const rerender = (patch) => { props = { ...props, ...patch }; act(() => r.update(React.createElement(Probe, props))); };
  const goOffline = () => { netState = { isConnected: false, isInternetReachable: false }; rerender({}); };
  const goOnline = () => { netState = { isConnected: true, isInternetReachable: true }; rerender({}); };
  return { calls, rerender, goOffline, goOnline };
}

console.log('useReloadOnReconnect');
test('끊김 → 로딩 실패 → 복귀: 한 번 다시 불러온다', () => {
  const s = setup(false);
  s.goOffline();
  s.rerender({ failed: true });
  assert.equal(s.calls.n, 0);
  s.goOnline();
  assert.equal(s.calls.n, 1);
});
test('끊김 → 복귀인데 화면이 정상: 건드리지 않는다', () => {
  const s = setup(false);
  s.goOffline(); s.goOnline();
  assert.equal(s.calls.n, 0);
});
test('계속 온라인에서 실패: 자동 재시도 없음(버튼으로만)', () => {
  const s = setup(false);
  s.rerender({ failed: true });
  assert.equal(s.calls.n, 0);
});
test('복귀 후 같은 실패로 다시 렌더되어도 중복 호출 없음', () => {
  const s = setup(false);
  s.goOffline(); s.rerender({ failed: true }); s.goOnline();
  s.rerender({ failed: true }); s.rerender({});
  assert.equal(s.calls.n, 1);
});
test('끊겼다 돌아오기를 두 번 하면 실패 상태일 때마다 한 번씩', () => {
  const s = setup(false);
  s.goOffline(); s.rerender({ failed: true }); s.goOnline();
  s.goOffline(); s.goOnline();
  assert.equal(s.calls.n, 2);
});
test('처음부터 실패 상태로 마운트 + 이미 온라인: 호출 없음', () => {
  const s = setup(true);
  assert.equal(s.calls.n, 0);
});
test('reload 가 매 렌더 새 함수여도 최신 것이 불린다', () => {
  const s = setup(false);
  const seen = [];
  s.goOffline(); s.rerender({ failed: true, reload: () => seen.push('new') }); s.goOnline();
  assert.deepEqual(seen, ['new']);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
