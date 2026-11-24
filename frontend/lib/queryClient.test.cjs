/* 서버 응답 캐시 시험 — 폰·서버 없이 node 로 돈다:  node lib/queryClient.test.cjs
 * (services/transientFailure.test.cjs 와 같은 방식 — typescript 를 require 훅으로 건다. node_modules 가 필요하다) */
const assert = require('assert/strict');
require('./motion/register.cjs');
const { queryClient, queryKeys, STALE } = require('./queryClient');
const { bumpSessionVersion } = require('../services/sessionVersion');

let passed = 0, failed = 0;
async function test(name, fn) {
  queryClient.clear();
  try { await fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

// queryFn 호출 횟수를 세는 가짜 요청
function counter(value = { ok: true }) {
  const fn = async () => { fn.calls++; return value; };
  fn.calls = 0;
  return fn;
}

(async () => {
  console.log('신선도(staleTime)');
  await test('신선하면 서버에 다시 안 간다', async () => {
    const q = counter();
    const opts = { queryKey: queryKeys.calendar(2026, 9), queryFn: q, staleTime: STALE.immutable };
    await queryClient.fetchQuery(opts);
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 1);
  });
  await test('staleTime 0 이면 매번 간다 — 이번 달·오늘은 예전 동작 그대로', async () => {
    const q = counter();
    const opts = { queryKey: queryKeys.daily('2026-11-24'), queryFn: q, staleTime: 0 };
    await queryClient.fetchQuery(opts);
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 2);
  });
  await test('키가 다르면(다른 달·다른 날짜) 따로 받는다', async () => {
    const q = counter();
    await queryClient.fetchQuery({ queryKey: queryKeys.calendar(2026, 8), queryFn: q, staleTime: STALE.immutable });
    await queryClient.fetchQuery({ queryKey: queryKeys.calendar(2026, 9), queryFn: q, staleTime: STALE.immutable });
    assert.equal(q.calls, 2);
  });
  await test('관찰자가 없어도 gcTime 안에서는 살아 있다 — 30분 신선도가 무의미해지지 않는다', async () => {
    const q = counter();
    await queryClient.fetchQuery({ queryKey: queryKeys.calendar(2026, 9), queryFn: q, staleTime: STALE.immutable });
    assert.ok(queryClient.getDefaultOptions().queries.gcTime >= STALE.immutable);
  });

  console.log('실패');
  await test('실패는 재시도 없이 바로 던지고 캐시하지 않는다 (기존 화면은 재시도하지 않았다)', async () => {
    let calls = 0;
    const failing = async () => { calls++; throw new Error('boom'); };
    const opts = { queryKey: queryKeys.daily('2026-11-01'), queryFn: failing, staleTime: STALE.immutable };
    await assert.rejects(() => queryClient.fetchQuery(opts), /boom/);
    assert.equal(calls, 1);
    await assert.rejects(() => queryClient.fetchQuery(opts), /boom/); // 다시 시도하면 다시 간다
    assert.equal(calls, 2);
  });

  console.log('무효화');
  await test('invalidate 하면 신선해도 다음 호출이 서버에 간다 (그룹장 넘기기·온보딩 저장)', async () => {
    const q = counter();
    const opts = { queryKey: queryKeys.groupDetail(7), queryFn: q, staleTime: STALE.group };
    await queryClient.fetchQuery(opts);
    await queryClient.invalidateQueries({ queryKey: queryKeys.groupDetail(7) });
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 2);
  });
  await test('온보딩 접두 무효화가 이메일별 키를 전부 덮는다', async () => {
    const q = counter();
    const opts = { queryKey: queryKeys.onboarding('a@x.com'), queryFn: q, staleTime: STALE.profile };
    await queryClient.fetchQuery(opts);
    await queryClient.invalidateQueries({ queryKey: queryKeys.onboardingAll });
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 2);
  });
  await test('모임 나가기: 그 모임 키만 지우고 다른 모임은 남긴다', async () => {
    const a = counter({ id: 1 }), b = counter({ id: 2 });
    await queryClient.fetchQuery({ queryKey: queryKeys.groupDetail(1), queryFn: a, staleTime: STALE.group });
    await queryClient.fetchQuery({ queryKey: queryKeys.groupDetail(2), queryFn: b, staleTime: STALE.group });
    queryClient.removeQueries({ queryKey: [...queryKeys.groupAll, 1] });
    assert.equal(queryClient.getQueryData(queryKeys.groupDetail(1)), undefined);
    assert.deepEqual(queryClient.getQueryData(queryKeys.groupDetail(2)), { id: 2 });
  });
  await test('setQueryData 로 고친 값이 다음 fetchQuery 에서 서버 없이 나온다 (TTS 변경·코드 재발급)', async () => {
    const q = counter({ ttsEnabled: true });
    const key = queryKeys.tts('a@x.com');
    await queryClient.fetchQuery({ queryKey: key, queryFn: q, staleTime: STALE.profile });
    queryClient.setQueryData(key, { ttsEnabled: false });
    const got = await queryClient.fetchQuery({ queryKey: key, queryFn: q, staleTime: STALE.profile });
    assert.deepEqual(got, { ttsEnabled: false });
    assert.equal(q.calls, 1);
  });

  console.log('세션 전환');
  await test('🔴 세션이 바뀌면(로그인·로그아웃·강제 로그아웃) 캐시가 비워진다 — 앞 사람의 프로필이 다음 사람에게 안 보인다', async () => {
    const q = counter({ owner: 'A' });
    const opts = { queryKey: queryKeys.onboarding('a@x.com'), queryFn: q, staleTime: STALE.profile };
    await queryClient.fetchQuery(opts);
    assert.deepEqual(queryClient.getQueryData(opts.queryKey), { owner: 'A' });
    bumpSessionVersion();
    assert.equal(queryClient.getQueryData(opts.queryKey), undefined);
    await queryClient.fetchQuery(opts);
    assert.equal(q.calls, 2);
  });

  console.log(`\n${passed} 통과, ${failed} 실패`);
  process.exit(failed ? 1 : 0);
})();
