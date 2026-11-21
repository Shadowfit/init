/* 릴리스 빌드(preview·production)에 필요한 EXPO_PUBLIC_* 가 들어 있는지 빌드 단계에서 확인한다.
 *
 * 왜 필요한가 — 이 값들이 없으면 앱은 **에러 없이** 뜬다(frontend/.env.example 맨 위 경고). 카메라도 켜지고 화면도 정상인데
 * 서버 주소가 비어 있거나 AI 토큰이 없어 실시간 분석만 조용히 안 돈다. 베타 사용자에게 나간 뒤에야 알게 되는 실패라, 빌드에서 끊는다.
 *
 * 쓰는 곳:
 *  - EAS Build 가 npm install 뒤에 `eas-build-post-install` 훅(package.json)으로 부른다. 프로파일은 EAS_BUILD_PROFILE 로 온다.
 *    development(dev client)는 로컬 서버를 쓰므로 검사하지 않고 preview·production 만 본다.
 *  - 손으로:  EAS_BUILD_PROFILE=production EXPO_PUBLIC_API_BASE_URL=https://… node scripts/check-release-env.cjs
 *
 * ⚠️ EAS 가 이 훅을 실제로 부르는지는 이 저장소에서 EAS 빌드를 돌려 본 적이 없어 **확인하지 못했다**(계정·프로젝트가 아직 없다).
 *    훅 이름은 EAS 문서의 npm 훅 규칙을 따랐다. 첫 빌드 로그에서 «릴리스 설정 점검» 줄이 보이는지 꼭 확인할 것.
 */
const CHECKED_PROFILES = new Set(['preview', 'production']);

// 릴리스에 있으면 안 되는 개발·시험용 값 — 켜진 채 나가면 실서버 대신 개발 프록시를 쓰거나 시험 모드로 동작한다.
const FORBIDDEN = {
  EXPO_PUBLIC_API_VIA_METRO: 'Metro 개발 프록시 경유 — 릴리스에는 Metro 가 없다',
  EXPO_PUBLIC_FAST_CAPTURE: '프레임 캡처 시험 모드',
  EXPO_PUBLIC_BACKEND_PORT: '개발용 포트 지정(격리 스택용)',
  EXPO_PUBLIC_AI_PORT: '개발용 포트 지정(격리 스택용)',
};

const PLACEHOLDER = /^REPLACE_WITH/i;

/** @returns {{errors: string[], warnings: string[]}} 프로파일이 검사 대상이 아니면 둘 다 빈 배열. */
function validateReleaseEnv(env, profile) {
  const errors = [];
  const warnings = [];
  if (!CHECKED_PROFILES.has(profile)) return { errors, warnings };

  const get = (k) => (env[k] ?? '').trim();

  // 서버 주소 둘 — 릴리스는 https 만 받는다(api.ts·aiService.ts 가 같은 규칙으로 거절한다. 거기서 거절하면 앱이 «설정 오류» 로만 뜬다).
  for (const key of ['EXPO_PUBLIC_API_BASE_URL', 'EXPO_PUBLIC_AI_BASE_URL']) {
    const v = get(key);
    if (!v) errors.push(`${key} 가 비어 있다 — 릴리스는 https 주소를 빌드에 주입해야 한다`);
    else if (!/^https:\/\/[^/\s?#]+/.test(v)) errors.push(`${key} 가 올바른 https 주소가 아니다(=${v}) — 평문 HTTP 는 앱에서 차단된다`);
  }
  const ai = get('EXPO_PUBLIC_AI_BASE_URL');
  if (/^https:\/\//.test(ai) && !/\/api\/v1\/?$/.test(ai)) {
    warnings.push(`EXPO_PUBLIC_AI_BASE_URL 이 /api/v1 로 끝나지 않는다(=${ai}) — 예시는 .env.example 의 http://…:8000/api/v1 모양이다. 의도한 것이 맞는지 확인`);
  }

  // AI 공개 토큰 — 없으면 프레임 폴링 자체가 안 켜진다(exercise.tsx). 예시 문구가 그대로 남아 있는 것도 «없음» 과 같다.
  const token = get('EXPO_PUBLIC_AI_PUBLIC_TOKEN');
  if (!token) errors.push('EXPO_PUBLIC_AI_PUBLIC_TOKEN 이 비어 있다 — 없으면 실시간 분석이 조용히 안 켜진다');
  else if (PLACEHOLDER.test(token)) errors.push('EXPO_PUBLIC_AI_PUBLIC_TOKEN 이 .env.example 의 예시 문구 그대로다');

  // 약관·처리방침 주소 — 비어 있으면 동의 화면의 «보기» 가 «문서가 아직 게시되지 않았어요» 만 보여 준다. 동의가 의미가 없어진다.
  for (const key of ['EXPO_PUBLIC_TERMS_URL', 'EXPO_PUBLIC_PRIVACY_URL']) {
    const v = get(key);
    if (!v) errors.push(`${key} 가 비어 있다 — 동의 화면에서 문서를 볼 수 없다`);
    else if (!/^https?:\/\//.test(v)) errors.push(`${key} 가 http(s) 주소가 아니다(=${v})`);
  }

  for (const [key, why] of Object.entries(FORBIDDEN)) {
    if (get(key)) errors.push(`${key} 가 설정돼 있다 — ${why}. 릴리스에서는 지울 것`);
  }

  return { errors, warnings };
}

module.exports = { validateReleaseEnv, CHECKED_PROFILES };

if (require.main === module) {
  const profile = process.env.EAS_BUILD_PROFILE || '';
  if (!CHECKED_PROFILES.has(profile)) {
    console.log(`[릴리스 설정 점검] 프로파일 '${profile || '(없음)'}' 은 검사 대상이 아니다 — 건너뜀 (대상: ${[...CHECKED_PROFILES].join(', ')})`);
    process.exit(0);
  }
  const { errors, warnings } = validateReleaseEnv(process.env, profile);
  for (const w of warnings) console.warn(`[릴리스 설정 점검] 주의: ${w}`);
  if (errors.length) {
    console.error(`[릴리스 설정 점검] '${profile}' 빌드를 중단한다 — ${errors.length}건:`);
    for (const e of errors) console.error(`  ✗ ${e}`);
    console.error('값은 eas.json 의 프로파일 env 또는 EAS 환경 변수에서 넣는다(docs/20-eas-build-guide.md).');
    process.exit(1);
  }
  console.log(`[릴리스 설정 점검] '${profile}' 통과`);
}
