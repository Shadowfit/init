/* recordsVersion 시험 — 폰·서버 없이 node 로 돈다:  node utils/recordsVersion.test.cjs
 * 「운동 기록이 바뀌었다」 신호가 캐시와 어떻게 맞물리는지 본다(#916 캐시 × #921 기록 삭제). */
const assert = require('assert/strict');
require('../lib/motion/register.cjs');
const { queryClient, queryKeys, STALE } = require('../lib/queryClient');
const { bumpRecordsVersion, currentRecordsVersion } = require('./recordsVersion');

let passed = 0, failed = 0;
async function test(name, fn) {
  queryClient.clear();
  try { await fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}
function counter(value = { ok: true }) {
  const fn = async () => { fn.calls++; return value; };
  fn.calls = 0;
  return fn;
}

(async () => {
  console.log('기록이 바뀌었을 때 캐시');
  await test('🔴 지난 날짜 일별 목록은 30분 캐시 중이어도, 기록이 바뀌면 다음 호출이 서버로 간다', async () => {
    const q = counter({ sessions: [{ id: 7 }] });
    const opts = { queryKey: queryKeys.daily('2026-11-01'), queryFn: q, staleTime: STALE.immutable };
    await queryClient.fetchQuery(opts);
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 1, '바뀌기 전에는 캐시를 쓴다');
    bumpRecordsVersion(); // 리포트에서 세션을 지웠다
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 2, '지운 세션이 남은 캐시가 돌아오면 안 된다');
  });
  await test('🔴 지난 달 캘린더도 마찬가지다 (지운 날의 점 표시)', async () => {
    const q = counter({ records: [] });
    const opts = { queryKey: queryKeys.calendar(2026, 10), queryFn: q, staleTime: STALE.immutable };
    await queryClient.fetchQuery(opts);
    bumpRecordsVersion();
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 2);
  });
  await test('기록과 무관한 캐시(모임 상세·프로필)는 남는다 — 불필요한 재요청을 만들지 않는다', async () => {
    const g = counter({ id: 1 }), m = counter({ level: 'A' });
    const go = { queryKey: queryKeys.groupDetail(1), queryFn: g, staleTime: STALE.group };
    const mo = { queryKey: queryKeys.onboarding('a@x.com'), queryFn: m, staleTime: STALE.profile };
    await queryClient.fetchQuery(go);
    await queryClient.fetchQuery(mo);
    bumpRecordsVersion();
    await queryClient.fetchQuery(go);
    await queryClient.fetchQuery(mo);
    assert.equal(g.calls, 1);
    assert.equal(m.calls, 1);
  });

  console.log('버전 카운터');
  await test('bump 할 때마다 올라간다 — 홈이 «바뀌었나» 를 비교한다', async () => {
    const before = currentRecordsVersion();
    bumpRecordsVersion();
    bumpRecordsVersion();
    assert.equal(currentRecordsVersion(), before + 2);
  });

  console.log(`\n${passed} 통과, ${failed} 실패`);
  process.exit(failed ? 1 : 0);
})();
