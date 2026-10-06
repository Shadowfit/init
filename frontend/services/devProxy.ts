import { Platform } from 'react-native';
import Constants from 'expo-constants';

/**
 * 개발 중 API 를 Metro 서버 경유로 보낼 때의 출처(origin). 아니면 null.
 *
 * 폰 시연에서 PC 방화벽이 도커 포트(백엔드·AI)로 들어오는 접속을 막아도, 폰이 이미 붙어 있는
 * Metro(8081)는 열려 있다 — metro.config.js 의 프록시가 /__backend, /__ai 로 받아 PC 안에서
 * 전달한다. EXPO_PUBLIC_API_VIA_METRO=1 이고 개발 빌드일 때만 켜진다.
 */
export function metroProxyOrigin(): string | null {
  if (!__DEV__ || process.env.EXPO_PUBLIC_API_VIA_METRO !== '1') return null;
  if (Platform.OS === 'web') {
    return typeof window !== 'undefined' ? window.location.origin : null;
  }
  const hostUri = Constants.expoConfig?.hostUri ?? '';
  return hostUri ? `http://${hostUri}` : null;
}
