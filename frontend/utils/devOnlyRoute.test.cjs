/* DevOnlyRoute 시험 — 릴리스에서 딥링크로 목업 화면(공지·Q&A)에 못 닿는지 실제로 렌더링해 본다:
 *   node utils/devOnlyRoute.test.cjs
 * expo-router 는 네이티브·라우터 환경이 필요해 Redirect 만 바꿔치기한다. .tsx 는 이 파일 안에서 변환 훅을 건다. */
const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const Module = require('module');
const ts = require('typescript');
require('../lib/motion/register.cjs');

require.extensions['.tsx'] = (module, filename) => {
  const out = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  module._compile(out, filename);
};

const React = require('react');
const origLoad = Module._load;
Module._load = function (request, ...rest) {
  if (request === 'expo-router') {
    return { Redirect: (props) => React.createElement('redirect', { href: props.href }) };
  }
  return origLoad.call(this, request, ...rest);
};

const TestRenderer = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const DevOnlyRoute = require(path.join(__dirname, '..', 'components', 'ui', 'DevOnlyRoute.tsx')).default;

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

function render(dev, child) {
  globalThis.__DEV__ = dev;
  let r;
  TestRenderer.act(() => { r = TestRenderer.create(React.createElement(DevOnlyRoute, null, child)); });
  return r.toJSON();
}
const Body = () => React.createElement('body-marker', null, '목업 화면');

console.log('DevOnlyRoute');
test('릴리스(__DEV__=false): 홈으로 리다이렉트하고 화면은 그리지 않는다', () => {
  const tree = render(false, React.createElement(Body));
  assert.equal(tree.type, 'redirect');
  assert.equal(tree.props.href, '/(tabs)');
  assert.equal(JSON.stringify(tree).includes('목업 화면'), false);
});
test('개발(__DEV__=true): 화면이 그대로 열린다', () => {
  const tree = render(true, React.createElement(Body));
  assert.equal(tree.type, 'body-marker');
});

// 두 화면이 실제로 이 래퍼로 감싸져 있는지 — 래퍼만 있고 안 쓰면 아무것도 막지 못한다.
console.log('공지·Q&A 화면이 래퍼를 쓴다');
for (const file of ['notice.tsx', 'qna.tsx']) {
  test(`${file}: DevOnlyRoute 로 본문을 감싼 기본 export`, () => {
    const src = fs.readFileSync(path.join(__dirname, '..', 'app', 'board', file), 'utf8');
    assert.match(src, /import DevOnlyRoute from '@\/components\/ui\/DevOnlyRoute'/);
    assert.match(src, /export default function \w+\(\) \{\s*return \(\s*<DevOnlyRoute>\s*<\w+Body \/>\s*<\/DevOnlyRoute>\s*\);\s*\}/);
  });
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
