import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

const WEB_PREFIX = 'shadowfit:';

function webSessionStorage(): Storage | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

// expo-secure-store has no web implementation. Keep web tokens in the current
// browser tab; native apps continue using the device's secure storage.
export const authStorage = {
  async getItemAsync(key: string): Promise<string | null> {
    if (Platform.OS === 'web') {
      return webSessionStorage()?.getItem(`${WEB_PREFIX}${key}`) ?? null;
    }
    return SecureStore.getItemAsync(key);
  },

  async setItemAsync(key: string, value: string): Promise<void> {
    if (Platform.OS === 'web') {
      const storage = webSessionStorage();
      if (!storage) throw new Error('브라우저 세션 저장소를 사용할 수 없습니다.');
      storage.setItem(`${WEB_PREFIX}${key}`, value);
      return;
    }
    await SecureStore.setItemAsync(key, value);
  },

  async deleteItemAsync(key: string): Promise<void> {
    if (Platform.OS === 'web') {
      webSessionStorage()?.removeItem(`${WEB_PREFIX}${key}`);
      return;
    }
    await SecureStore.deleteItemAsync(key);
  },
};
