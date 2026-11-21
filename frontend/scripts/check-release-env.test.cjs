/* validateReleaseEnv 시험 — 폰·서버 없이 node 로 돈다:  node scripts/check-release-env.test.cjs */
const assert = require('assert/strict');
const { validateReleaseEnv } = require('./check-release-env.cjs');

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log(`  ✓ ${name}`); }
  catch (e) { failed++; console.log(`  ✗ ${name}\n      ${e.message}`); }
}

const GOOD = {
  EXPO_PUBLIC_API_BASE_URL: 'https://api.example.com',
  EXPO_PUBLIC_AI_BASE_URL: 'https://ai.example.com/api/v1',
  EXPO_PUBLIC_AI_PUBLIC_TOKEN: 'abc123',
  EXPO_PUBLIC_TERMS_URL: 'https://example.com/terms',
  EXPO_PUBLIC_PRIVACY_URL: 'https://example.com/privacy',
};

console.log('validateReleaseEnv');
test('모두 있으면 production 통과(오류 0·주의 0)', () => {
  assert.deepEqual(validateReleaseEnv(GOOD, 'production'), { errors: [], warnings: [] });
});
test('preview 도 같은 기준이다', () => assert.equal(validateReleaseEnv({}, 'preview').errors.length > 0, true));
test('development 는 검사하지 않는다(빈 환경도 통과)', () => {
  assert.deepEqual(validateReleaseEnv({}, 'development'), { errors: [], warnings: [] });
});
test('프로파일이 없으면 검사하지 않는다', () => assert.deepEqual(validateReleaseEnv({}, ''), { errors: [], warnings: [] }));
test('전부 비면 필수 5개가 오류다', () => assert.equal(validateReleaseEnv({}, 'production').errors.length, 5));
test('http 주소는 거절 — 평문 HTTP', () => {
  const r = validateReleaseEnv({ ...GOOD, EXPO_PUBLIC_API_BASE_URL: 'http://api.example.com' }, 'production');
  assert.equal(r.errors.length, 1);
  assert.match(r.errors[0], /EXPO_PUBLIC_API_BASE_URL/);
});
test('"https://" 만 있는 값(호스트 없음)은 거절', () => {
  assert.equal(validateReleaseEnv({ ...GOOD, EXPO_PUBLIC_AI_BASE_URL: 'https://' }, 'production').errors.length, 1);
});
test('토큰이 .env.example 예시 문구 그대로면 거절', () => {
  const r = validateReleaseEnv({ ...GOOD, EXPO_PUBLIC_AI_PUBLIC_TOKEN: 'REPLACE_WITH_SAME_VALUE_AS_ROOT_AI_PUBLIC_TOKEN' }, 'production');
  assert.equal(r.errors.length, 1);
});
test('공백만 있는 값은 없는 것과 같다', () => {
  assert.equal(validateReleaseEnv({ ...GOOD, EXPO_PUBLIC_AI_PUBLIC_TOKEN: '   ' }, 'production').errors.length, 1);
});
test('약관 주소가 없으면 오류', () => {
  const rest = { ...GOOD };
  delete rest.EXPO_PUBLIC_TERMS_URL;
  assert.equal(validateReleaseEnv(rest, 'production').errors.length, 1);
});
test('개발용 값이 켜져 있으면 오류(Metro 프록시·캡처 모드·포트)', () => {
  const r = validateReleaseEnv(
    { ...GOOD, EXPO_PUBLIC_API_VIA_METRO: '1', EXPO_PUBLIC_FAST_CAPTURE: '1', EXPO_PUBLIC_AI_PORT: '18000' },
    'production',
  );
  assert.equal(r.errors.length, 3);
});
test('AI 주소가 /api/v1 로 끝나지 않으면 오류가 아니라 주의', () => {
  const r = validateReleaseEnv({ ...GOOD, EXPO_PUBLIC_AI_BASE_URL: 'https://ai.example.com' }, 'production');
  assert.equal(r.errors.length, 0);
  assert.equal(r.warnings.length, 1);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
