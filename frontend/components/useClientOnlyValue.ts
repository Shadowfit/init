// This function is web-only as native doesn't currently support server (or build-time) rendering.
// _server 는 시그니처 통일용 (web 버전에서만 사용).
export function useClientOnlyValue<S, C>(_server: S, client: C): S | C {
  return client;
}
