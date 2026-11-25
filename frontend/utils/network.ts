import { useEffect, useRef } from 'react';
import { useNetInfo } from '@react-native-community/netinfo';
import { isOffline, shouldReloadOnReconnect } from '@/utils/offline';

/** 지금 오프라인인가(확실히 끊겼을 때만 true — utils/offline.ts). */
export function useIsOffline(): boolean {
  return isOffline(useNetInfo());
}

/**
 * 로딩이 실패한 화면이 연결 복귀 때 한 번 다시 불러오게 한다.
 *
 * @param failed 이 화면의 <b>첫 로딩이 실패한 상태</b>인가. true 가 아니면 복귀해도 아무것도 하지 않는다.
 * @param reload 다시 불러오는 함수. 매번 새 함수여도 되고(ref 로 최신을 쓴다) 효과를 다시 걸지 않는다.
 */
export function useReloadOnReconnect(failed: boolean, reload: () => void): void {
  const offline = useIsOffline();
  const prevOffline = useRef(offline);
  const reloadRef = useRef(reload);
  reloadRef.current = reload;

  useEffect(() => {
    if (shouldReloadOnReconnect(prevOffline.current, offline, failed)) {
      reloadRef.current();
    }
    prevOffline.current = offline;
  }, [offline, failed]);
}
