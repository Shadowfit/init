/* 운동 일지 카드·입력 모달 렌더링 시험 — react-test-renderer 로 실제 상태 전이를 돌린다:
 *   node utils/dailyLogUi.test.cjs
 * react-native 는 네이티브가 필요해 호스트 요소(문자열 타입)로 바꿔치기하고, '@/' 별칭은 frontend 루트로 푼다. */
const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const Module = require('module');
const ts = require('typescript');
require('../lib/motion/register.cjs');

const ROOT = path.join(__dirname, '..');
require.extensions['.tsx'] = (module, filename) => {
  const out = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  module._compile(out, filename);
};

const React = require('react');
const host = (name) => (p) => React.createElement(name, p, p.children);
const RN = {
  View: host('View'), Text: host('Text'), TextInput: host('TextInput'), TouchableOpacity: host('TouchableOpacity'),
  KeyboardAvoidingView: host('KeyboardAvoidingView'), ActivityIndicator: host('ActivityIndicator'),
  Modal: (p) => (p.visible ? React.createElement('Modal', p, p.children) : null),
  Platform: { OS: 'ios' }, StyleSheet: { create: (s) => s },
};
const origLoad = Module._load;
Module._load = function (request, ...rest) {
  if (request === 'react-native') return RN;
  if (request.startsWith('@/')) return origLoad.call(this, path.join(ROOT, request.slice(2)), ...rest);
  return origLoad.call(this, request, ...rest);
};

const TestRenderer = require('react-test-renderer');
const { act } = TestRenderer;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const Card = require(path.join(ROOT, 'components', 'home', 'DailyLogCard.tsx')).default;
const Modal = require(path.join(ROOT, 'components', 'home', 'DailyLogModal.tsx')).default;

let passed = 0, failed = 0;
async function test(name, fn) {
  try { await fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}
const texts = (r) => r.root.findAll((n) => n.type === 'Text').map((n) => [].concat(n.props.children).join(''));
const byId = (r, id) => r.root.findAll((n) => n.props && n.props.testID === id && typeof n.type === 'string');
const render = (el) => { let r; act(() => { r = TestRenderer.create(el); }); return r; };
const buttonByTitle = (r, title) => {
  const t = r.root.findAll((n) => n.type === 'Text' && [].concat(n.props.children).join('') === title)[0];
  // 텍스트에서 위로 올라가 Button 이 그린 TouchableOpacity 호스트 요소를 찾는다(사이에 스텁 컴포넌트가 끼어 있다).
  let n = t.parent;
  while (n && n.type !== 'TouchableOpacity') n = n.parent;
  return n;
};

(async () => {
  console.log('DailyLogCard');
  await test('기록이 없으면 «남겨보세요» 안내와 «작성» 버튼 — 에러가 아니다', () => {
    const r = render(React.createElement(Card, { memo: null, mood: null, onEdit() {} }));
    assert.equal(byId(r, 'daily-log-empty').length, 1);
    assert.equal(byId(r, 'daily-log-memo').length, 0);
    assert.equal(byId(r, 'daily-log-edit')[0].props.accessibilityLabel, '운동 일지 작성');
    assert.ok(texts(r).includes('작성'));
  });
  await test('기분만 있으면 이모지·문구를 보이고 «수정»', () => {
    const r = render(React.createElement(Card, { memo: null, mood: 'GOOD', onEdit() {} }));
    assert.ok(texts(r).includes('🙂 좋아요'));
    assert.equal(byId(r, 'daily-log-memo').length, 0);
    assert.equal(byId(r, 'daily-log-empty').length, 0);
    assert.ok(texts(r).includes('수정'));
  });
  await test('메모와 기분이 둘 다 있으면 둘 다 보인다', () => {
    const r = render(React.createElement(Card, { memo: '무릎이 덜 아팠다', mood: 'GREAT', onEdit() {} }));
    assert.ok(texts(r).includes('😄 최고예요'));
    assert.ok(texts(r).includes('무릎이 덜 아팠다'));
  });
  await test('서버가 모르는 기분 값을 주면 «기분 없음» 으로 본다 — 깨지지 않는다', () => {
    const r = render(React.createElement(Card, { memo: null, mood: 'ECSTATIC', onEdit() {} }));
    assert.equal(byId(r, 'daily-log-empty').length, 1);
  });
  await test('«수정/작성» 을 누르면 onEdit 이 불린다', () => {
    let n = 0;
    const r = render(React.createElement(Card, { memo: 'm', mood: null, onEdit: () => { n++; } }));
    act(() => { byId(r, 'daily-log-edit')[0].props.onPress(); });
    assert.equal(n, 1);
  });

  console.log('DailyLogModal');
  const mk = (over = {}) => {
    const calls = { saved: [], closed: 0 };
    const props = {
      visible: true, dateLabel: '11월 25일', initialMemo: null, initialMood: null,
      onClose: () => { calls.closed++; },
      onSave: async (d) => { calls.saved.push(d); },
      ...over,
    };
    return { calls, props, r: render(React.createElement(Modal, props)) };
  };
  await test('서버에 저장된 값으로 시작한다(안 보이는 값을 덮어 지우지 않게)', () => {
    const { r } = mk({ initialMemo: '저장된 메모', initialMood: 'BAD' });
    assert.equal(byId(r, 'daily-log-memo-input')[0].props.value, '저장된 메모');
    assert.equal(byId(r, 'daily-log-mood-BAD')[0].props.accessibilityState.selected, true);
    assert.equal(byId(r, 'daily-log-mood-GOOD')[0].props.accessibilityState.selected, false);
  });
  await test('변경이 없으면 저장 버튼이 꺼져 있다', () => {
    const { r } = mk({ initialMemo: '메모', initialMood: 'GOOD' });
    assert.equal(buttonByTitle(r, '저장').props.disabled, true);
  });
  await test('둘 다 비어 있던 날에 아무것도 안 쓰면 저장 버튼이 꺼져 있다', () => {
    const { r } = mk();
    assert.equal(buttonByTitle(r, '저장').props.disabled, true);
  });
  await test('기분을 고르면 저장 버튼이 켜진다', () => {
    const { r } = mk();
    act(() => { byId(r, 'daily-log-mood-GREAT')[0].props.onPress(); });
    assert.equal(buttonByTitle(r, '저장').props.disabled, false);
  });
  await test('선택한 기분을 다시 누르면 해제된다(기분은 비워 둘 수 있다)', () => {
    const { r } = mk({ initialMood: 'GOOD' });
    act(() => { byId(r, 'daily-log-mood-GOOD')[0].props.onPress(); });
    assert.equal(byId(r, 'daily-log-mood-GOOD')[0].props.accessibilityState.selected, false);
    assert.equal(buttonByTitle(r, '저장').props.disabled, false); // 지움도 변경이다
  });
  await test('저장 성공: onSave 에 {memo, mood} 를 넘기고 닫는다', async () => {
    const { r, calls } = mk();
    act(() => { byId(r, 'daily-log-mood-GOOD')[0].props.onPress(); });
    act(() => { byId(r, 'daily-log-memo-input')[0].props.onChangeText('  오늘 좋았다 '); });
    await act(async () => { await buttonByTitle(r, '저장').props.onPress(); });
    assert.deepEqual(calls.saved, [{ memo: '  오늘 좋았다 ', mood: 'GOOD' }]); // 정규화는 요청을 만드는 쪽이 한다
    assert.equal(calls.closed, 1);
    assert.equal(byId(r, 'daily-log-error').length, 0);
  });
  await test('저장 실패: 닫지 않고 오류를 보이며, 쓴 글이 그대로 남는다', async () => {
    const { r, calls } = mk({ onSave: async () => { throw new Error('network'); } });
    act(() => { byId(r, 'daily-log-memo-input')[0].props.onChangeText('쓰던 글'); });
    await act(async () => { await buttonByTitle(r, '저장').props.onPress(); });
    assert.equal(calls.closed, 0);
    assert.equal(byId(r, 'daily-log-error').length, 1);
    assert.equal(byId(r, 'daily-log-memo-input')[0].props.value, '쓰던 글');
    assert.equal(buttonByTitle(r, '저장').props.disabled, false); // 다시 시도할 수 있다
  });
  await test('실패 뒤 다시 저장하면 성공하고 오류가 사라진다', async () => {
    let fail = true;
    const saved = [];
    const { r, calls } = mk({ onSave: async (d) => { if (fail) throw new Error('x'); saved.push(d); } });
    act(() => { byId(r, 'daily-log-memo-input')[0].props.onChangeText('글'); });
    await act(async () => { await buttonByTitle(r, '저장').props.onPress(); });
    assert.equal(byId(r, 'daily-log-error').length, 1);
    fail = false;
    await act(async () => { await buttonByTitle(r, '저장').props.onPress(); });
    assert.equal(saved.length, 1);
    assert.equal(calls.closed, 1);
    assert.equal(byId(r, 'daily-log-error').length, 0);
  });
  await test('«취소» 는 저장하지 않고 닫는다', () => {
    const { r, calls } = mk();
    act(() => { buttonByTitle(r, '취소').props.onPress(); });
    assert.equal(calls.closed, 1);
    assert.equal(calls.saved.length, 0);
  });
  await test('열 때마다 서버 값으로 초기화된다(쓰다 닫은 초안이 다른 날짜에 남지 않는다)', () => {
    const props = { visible: true, dateLabel: 'A', initialMemo: 'A날 메모', initialMood: 'GOOD', onClose() {}, onSave: async () => {} };
    const r = render(React.createElement(Modal, props));
    act(() => { byId(r, 'daily-log-memo-input')[0].props.onChangeText('쓰다 만 글'); });
    act(() => { r.update(React.createElement(Modal, { ...props, visible: false })); });
    act(() => { r.update(React.createElement(Modal, { ...props, visible: true, dateLabel: 'B', initialMemo: 'B날 메모', initialMood: null })); });
    assert.equal(byId(r, 'daily-log-memo-input')[0].props.value, 'B날 메모');
    assert.equal(byId(r, 'daily-log-mood-GOOD')[0].props.accessibilityState.selected, false);
  });
  await test('닫혀 있으면(visible=false) 아무것도 그리지 않는다', () => {
    const r = render(React.createElement(Modal, { visible: false, dateLabel: 'x', onClose() {}, onSave: async () => {} }));
    assert.equal(r.toJSON(), null);
  });
  await test('제목에 날짜가 들어간다', () => {
    const { r } = mk({ dateLabel: '3월 1일' });
    assert.ok(texts(r).includes('3월 1일 운동 일지'));
  });

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})();
