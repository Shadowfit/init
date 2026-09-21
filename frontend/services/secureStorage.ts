import { Platform } from 'react-native';
import * as ExpoSecureStore from 'expo-secure-store';

// expo-secure-store 는 iOS/Android 전용이다 — 웹에는 구현이 없어 SDK 57 부터는 호출하면
// «deleteValueWithKeyAsync is not a function» 으로 터진다(54 까지는 스텁이 조용히 넘어갔다).
// 웹은 개발 중 화면 확인용이라 localStorage 로 대체한다. 🔴 localStorage 는 암호화되지 않는다 —
// 웹을 실제 배포 대상으로 삼게 되면 토큰 보관 방식(httpOnly 쿠키 등)을 따로 정해야 한다.
//
// 함수 이름을 expo-secure-store 와 똑같이 둔 이유: 쓰는 쪽(api.ts, authStore.ts)이 import 경로만 바꾸면 되게.
const isWeb = Platform.OS === 'web';

function webStorage(): Storage | null {
  // 정적 렌더링(SSR) 중에는 window 가 없다
  return typeof window !== 'undefined' ? window.localStorage : null;
}

export async function getItemAsync(key: string): Promise<string | null> {
  if (isWeb) return webStorage()?.getItem(key) ?? null;
  return ExpoSecureStore.getItemAsync(key);
}

export async function setItemAsync(key: string, value: string): Promise<void> {
  if (isWeb) {
    webStorage()?.setItem(key, value);
    return;
  }
  await ExpoSecureStore.setItemAsync(key, value);
}

export async function deleteItemAsync(key: string): Promise<void> {
  if (isWeb) {
    webStorage()?.removeItem(key);
    return;
  }
  await ExpoSecureStore.deleteItemAsync(key);
}
