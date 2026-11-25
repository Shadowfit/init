/**
 * 오프라인 판정 — React Native 를 import 하지 않는 순수 함수라 node 로 바로 시험된다
 * (`node utils/offline.test.cjs`). 훅은 utils/network.ts.
 */

/** @react-native-community/netinfo 의 NetInfoState 중 판정에 쓰는 두 필드. */
export interface NetState {
  isConnected: boolean | null;
  isInternetReachable: boolean | null;
}

/**
 * 오프라인인가.
 *
 * <b>모르면(null) 오프라인이 아니다.</b> 앱을 켠 직후 NetInfo 는 둘 다 null 이고, 그 순간 «오프라인» 배너를
 * 띄우면 멀쩡한 사용자가 매번 깜빡이는 배너를 본다. 확실히 끊겼을 때(false)만 오프라인이다.
 * isInternetReachable 은 와이파이는 붙었는데 인터넷이 안 되는 경우(공유기만 켜진 상태)를 잡는다.
 */
export function isOffline(state: NetState | null | undefined): boolean {
  if (!state) return false;
  return state.isConnected === false || state.isInternetReachable === false;
}

/**
 * 연결이 돌아왔을 때 다시 불러올까.
 *
 * 오프라인 → 온라인으로 <b>바뀐 순간</b>이고 <b>직전 로딩이 실패한 화면</b>일 때만 true.
 * 정상으로 떠 있는 화면은 건드리지 않는다 — 와이파이가 흔들릴 때마다 모든 화면이 깜빡이며 다시 불러오면
 * 오히려 서버에 몰리고 사용자는 보던 위치를 잃는다.
 */
export function shouldReloadOnReconnect(wasOffline: boolean, nowOffline: boolean, failed: boolean): boolean {
  return wasOffline && !nowOffline && failed;
}
