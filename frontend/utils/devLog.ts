/**
 * 개발 빌드에서만 찍는 로그.
 *
 * 화면들이 API 실패를 `console.warn/error` 로 남기는데, 인자에 서버 응답 본문(`e.response.data`)이 들어 있다.
 * 릴리스 빌드에서는 이 로그가 기기 로그(logcat / 콘솔)에 남아 응답에 섞인 개인 정보·내부 메시지가 새고,
 * 크래시 리포터를 붙이면 그대로 수집될 수 있다. 개발 중에는 그대로 보이고 릴리스에서는 사라지게 한다.
 *
 * 릴리스에서 남겨야 하는 오류는 로그가 아니라 크래시 리포터(Sentry 등)로 보낸다 — 그때 이 파일이 아니라
 * 보고 지점에서 개인 정보를 걸러서 보낸다.
 */
export function devWarn(...args: unknown[]): void {
  if (__DEV__) console.warn(...args);
}

export function devError(...args: unknown[]): void {
  if (__DEV__) console.error(...args);
}
