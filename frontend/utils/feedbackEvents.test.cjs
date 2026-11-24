/* feedbackEvents 시험 — 폰·서버 없이 node 로 돈다:  node utils/feedbackEvents.test.cjs */
const assert = require('assert/strict');
require('../lib/motion/register.cjs');
const { formatClock, feedbackLabel, syncText, toRows, visibleRows, FEEDBACK_PREVIEW } = require('./feedbackEvents');
const { currentRecordsVersion, bumpRecordsVersion } = require('./recordsVersion');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

console.log('formatClock');
test('ISO 로컬 시각 → HH:mm:ss', () => assert.equal(formatClock('2026-10-06T11:40:33'), '11:40:33'));
test('소수초·오프셋이 붙어도 앞 8자만', () => {
  assert.equal(formatClock('2026-10-06T11:40:33.123456'), '11:40:33');
  assert.equal(formatClock('2026-10-06T11:40:33+09:00'), '11:40:33');
});
test('읽을 수 없으면 «-»', () => {
  for (const bad of [null, undefined, 123, '', 'garbage', '2026-10-06']) assert.equal(formatClock(bad), '-');
});

console.log('feedbackLabel');
test('아는 종류는 한글 라벨', () => assert.equal(feedbackLabel('KNEE_OUT'), '무릎이 발끝보다 나감'));
test('모르는 종류(새 유형·옛 앱)는 코드 그대로', () => assert.equal(feedbackLabel('NEW_TYPE'), 'NEW_TYPE'));
test('문자열이 아니면 «-»', () => assert.equal(feedbackLabel(undefined), '-'));

console.log('syncText');
test('소수 첫째 자리', () => { assert.equal(syncText(82.04), '82%'); assert.equal(syncText(79.96), '80%'); assert.equal(syncText(81.25), '81.3%'); });
test('null·NaN·문자열 → undefined (점수 없음)', () => {
  for (const bad of [null, undefined, NaN, '80']) assert.equal(syncText(bad), undefined);
});

console.log('toRows');
const EVENTS = [
  { id: 1, feedbackType: 'KNEE_OUT', syncRateAtTrigger: 71.5, occurredAt: '2026-10-06T11:40:33' },
  { id: 2, feedbackType: 'HIP_LOW', syncRateAtTrigger: null, occurredAt: '2026-10-06T11:41:02' },
];
test('점수 있는 세션: 시각·라벨·싱크로율', () => {
  assert.deepEqual(toRows(EVENTS, true), [
    { key: 1, time: '11:40:33', label: '무릎이 발끝보다 나감', sync: '71.5%' },
    { key: 2, time: '11:41:02', label: '엉덩이 처짐', sync: undefined },
  ]);
});
test('점수를 못 낸 세션(hasScore=false)이면 싱크로율 칸을 비운다', () => {
  assert.ok(toRows(EVENTS, false).every((r) => r.sync === undefined));
});
test('배열이 아니거나 비어도 던지지 않는다', () => {
  assert.deepEqual(toRows(null, true), []);
  assert.deepEqual(toRows(undefined, true), []);
  assert.deepEqual(toRows([], true), []);
});
test('이벤트 하나가 null 이어도 나머지는 그린다', () => {
  const rows = toRows([null, EVENTS[0]], true);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].time, '-');
  assert.equal(rows[1].label, '무릎이 발끝보다 나감');
});

console.log('visibleRows');
const many = Array.from({ length: FEEDBACK_PREVIEW + 7 }, (_, i) => i);
test('접힘: 앞 N개와 남은 개수', () => {
  const v = visibleRows(many, false);
  assert.equal(v.shown.length, FEEDBACK_PREVIEW);
  assert.equal(v.hidden, 7);
});
test('펼침: 전부, 남은 개수 0', () => {
  const v = visibleRows(many, true);
  assert.equal(v.shown.length, many.length);
  assert.equal(v.hidden, 0);
});
test('N개 이하면 접어도 전부', () => {
  const v = visibleRows([1, 2, 3], false);
  assert.deepEqual(v, { shown: [1, 2, 3], hidden: 0 });
});

console.log('recordsVersion');
test('bump 할 때마다 올라간다 — 홈이 «바뀌었나» 를 비교할 수 있다', () => {
  const before = currentRecordsVersion();
  bumpRecordsVersion();
  bumpRecordsVersion();
  assert.equal(currentRecordsVersion(), before + 2);
});

console.log(`\n${passed} 통과, ${failed} 실패`);
process.exit(failed ? 1 : 0);
