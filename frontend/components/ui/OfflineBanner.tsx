import { View, Text, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WifiOff } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING } from '@/constants/Colors';
import { useIsOffline } from '@/utils/network';

/**
 * 연결이 끊겼을 때 화면 맨 위에 뜨는 안내. 앱 전체에 하나(app/_layout.tsx).
 *
 * 화면 위에 <b>겹쳐서</b> 띄우고 터치는 통과시킨다 — 레이아웃을 밀면 각 화면의 SafeAreaView 위쪽 여백과
 * 겹쳐 두 번 밀리고, 배너가 떴다 사라질 때 화면이 덜컹거린다. 대신 위쪽 가장자리를 잠깐 가리는데,
 * 끊긴 동안만이고 뒤로가기 버튼 등은 그대로 눌린다(pointerEvents="none").
 */
export default function OfflineBanner() {
  const offline = useIsOffline();
  const insets = useSafeAreaInsets();
  if (!offline) return null;

  return (
    <View
      pointerEvents="none"
      style={[styles.wrap, { paddingTop: insets.top + SPACING.xs }]}
      accessibilityRole="alert"
      accessibilityLiveRegion="polite"
    >
      <WifiOff size={14} color={COLORS.black} strokeWidth={2.25} />
      <Text style={styles.text}>인터넷에 연결되어 있지 않아요</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    zIndex: 1000,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.xs,
    paddingBottom: SPACING.xs,
    backgroundColor: COLORS.warning,
  },
  text: { fontSize: FONT_SIZE.xs, fontWeight: '600', color: COLORS.black },
});
