/**
 * 실패가 «서버가 잠시 못 받는 것»(네트워크 끊김·타임아웃·5xx)인가.
 *
 * 인증이 틀렸다는 뜻이 아니다. 재발급이 이런 이유로 실패했는데 «세션이 끝났다» 로 읽으면, 서버·DB 가 몇 초 흔들리는 사이에
 * 사용자가 로그아웃된다(#848 이 백엔드 쪽에서 막으려던 것과 같은 사고). 그래서 재발급 실패를 둘로 가른다:
 *  - 4xx(만료·폐기·위조 등 서버가 «이 토큰은 안 된다» 고 답함) → 세션 종료
 *  - 이 함수가 true(응답이 없거나 5xx) → 세션은 두고 요청만 실패시킨다
 *
 * 순수 함수라 node 로 바로 시험된다: `node services/transientFailure.test.cjs`
 */
export function isTransientFailure(error: unknown): boolean {
  const e = error as
    | { response?: { status?: number }; request?: unknown; code?: string }
    | null
    | undefined;
  if (!e || typeof e !== 'object') return false;

  const status = e.response?.status;
  if (typeof status === 'number') return status >= 500;

  // 응답이 없다 = 요청은 나갔는데 답을 못 받았다(네트워크 단절·타임아웃·DNS). axios 는 이때 response 없는 에러를 던진다.
  // 요청이 나가기도 전에 난 오류(설정 실수 등)는 request 도 code 도 없어 여기서 false 다 — 그건 일시적이 아니다.
  return !!e.request || e.code === 'ECONNABORTED' || e.code === 'ERR_NETWORK';
}
