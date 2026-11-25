/* 운동 일지 로직 시험 — 폰·서버 없이 node 로 돈다:  node utils/dailyLog.test.cjs
 *  · 메모 정규화·변경 판단·요청 본문·날짜 제한, 그리고 기분 목록이 백엔드 enum 과 어긋나지 않는지 */
const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
require('../lib/motion/register.cjs');
const {
  MOODS, MEMO_MAX, normalizeMemo, toDraft, isDirty, canWriteLog, buildDailyLogRequest, moodDisplay,
} = require('./dailyLog');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

console.log('normalizeMemo');
test('앞뒤 공백을 걷는다', () => assert.equal(normalizeMemo('  오늘 좋았다  '), '오늘 좋았다'));
test('비었거나 공백뿐이면 null', () => {
  assert.equal(normalizeMemo(''), null);
  assert.equal(normalizeMemo('   \n  '), null);
  assert.equal(normalizeMemo(null), null);
  assert.equal(normalizeMemo(undefined), null);
});
test('안의 줄바꿈·공백은 그대로 둔다', () => assert.equal(normalizeMemo('첫 줄\n둘째  줄'), '첫 줄\n둘째  줄'));
test(`${MEMO_MAX}자를 넘으면 자른다`, () => assert.equal(normalizeMemo('가'.repeat(MEMO_MAX + 50)).length, MEMO_MAX));
test(`${MEMO_MAX}자 이하는 그대로`, () => assert.equal(normalizeMemo('가'.repeat(MEMO_MAX)).length, MEMO_MAX));

console.log('toDraft — 서버 값 → 입력 초기값');
test('정상 값은 그대로', () => assert.deepEqual(toDraft('메모', 'GOOD'), { memo: '메모', mood: 'GOOD' }));
test('null 은 null', () => assert.deepEqual(toDraft(null, null), { memo: null, mood: null }));
test('모르는 기분 문자열(서버가 값을 늘린 경우)은 null — 앱이 깨지지 않는다', () =>
  assert.deepEqual(toDraft('m', 'ECSTATIC'), { memo: 'm', mood: null }));

console.log('isDirty — 저장 버튼을 켤지');
const base = { memo: '메모', mood: 'GOOD' };
test('같으면 변경 없음', () => assert.equal(isDirty(base, { memo: '메모', mood: 'GOOD' }), false));
test('공백만 다르면 같은 값', () => assert.equal(isDirty(base, { memo: '  메모 ', mood: 'GOOD' }), false));
test('메모가 다르면 변경', () => assert.equal(isDirty(base, { memo: '다른 메모', mood: 'GOOD' }), true));
test('기분이 다르면 변경', () => assert.equal(isDirty(base, { memo: '메모', mood: 'BAD' }), true));
test('기분을 지우면 변경(null 은 «지움»)', () => assert.equal(isDirty(base, { memo: '메모', mood: null }), true));
test('둘 다 비어 있던 날에 아무것도 안 쓰면 변경 없음', () =>
  assert.equal(isDirty({ memo: null, mood: null }, { memo: '   ', mood: null }), false));
test('메모를 전부 지우면 변경(지우기 허용)', () => assert.equal(isDirty(base, { memo: '', mood: 'GOOD' }), true));

console.log('buildDailyLogRequest — 한쪽만 보내면 다른 쪽이 지워지는 계약');
test('둘 다 항상 담는다', () =>
  assert.deepEqual(buildDailyLogRequest('2026-11-25', { memo: '  좋았다 ', mood: 'GOOD' }),
    { logDate: '2026-11-25', memo: '좋았다', mood: 'GOOD' }));
test('메모만 쓰면 mood 는 null 로 명시한다(필드를 빼지 않는다)', () => {
  const r = buildDailyLogRequest('2026-11-25', { memo: '메모', mood: null });
  assert.ok('mood' in r && r.mood === null);
});
test('기분만 고르면 memo 는 null', () => {
  const r = buildDailyLogRequest('2026-11-25', { memo: '   ', mood: 'GREAT' });
  assert.equal(r.memo, null);
  assert.equal(r.mood, 'GREAT');
});
test('날짜는 YYYY-MM-DD 문자열 그대로(백엔드 @JsonFormat)', () =>
  assert.equal(buildDailyLogRequest('2026-03-01', { memo: 'm', mood: null }).logDate, '2026-03-01'));

console.log('canWriteLog — 오늘까지만');
test('오늘은 가능', () => assert.equal(canWriteLog('2026-11-26', '2026-11-26'), true));
test('과거는 가능', () => assert.equal(canWriteLog('2026-01-05', '2026-11-26'), true));
test('미래는 불가', () => assert.equal(canWriteLog('2026-11-27', '2026-11-26'), false));
test('날짜 형식이 아니면 불가(빈 문자열 포함)', () => {
  assert.equal(canWriteLog('', '2026-11-26'), false);
  assert.equal(canWriteLog('2026/11/01', '2026-11-26'), false);
});
test('월·일 자릿수가 달라도 사전순 비교가 맞다(2026-02-09 < 2026-11-26)', () =>
  assert.equal(canWriteLog('2026-02-09', '2026-11-26'), true));

console.log('moodDisplay · MOODS');
test('기분 → 이모지·문구', () => assert.deepEqual(moodDisplay('GREAT'), { emoji: '😄', label: '최고예요' }));
test('없거나 모르는 값은 null', () => {
  assert.equal(moodDisplay(null), null);
  assert.equal(moodDisplay('ECSTATIC'), null);
});
test('기분 5개, 값이 겹치지 않고 모두 이모지·문구가 있다', () => {
  assert.equal(MOODS.length, 5);
  assert.equal(new Set(MOODS.map((m) => m.value)).size, 5);
  for (const m of MOODS) assert.ok(m.emoji && m.label);
});

console.log('백엔드 Mood enum 과 일치');
test('앱의 기분 값 목록이 백엔드 enum 과 같다(순서 포함)', () => {
  const javaPath = path.join(__dirname, '..', '..', 'backend', 'src', 'main', 'java', 'com', 'shadowfit', 'dto', 'report', 'record', 'Mood.java');
  if (!fs.existsSync(javaPath)) { console.log('      (백엔드 소스가 없는 환경 — 건너뜀)'); return; }
  const src = fs.readFileSync(javaPath, 'utf8');
  const body = src.match(/enum\s+Mood\s*\{([^}]*)\}/)[1];
  const values = body.split(',').map((s) => s.trim()).filter(Boolean);
  assert.deepEqual(MOODS.map((m) => m.value), values);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
