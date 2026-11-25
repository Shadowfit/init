import { View, Text, StyleSheet } from 'react-native';
import { COLORS, FONT_SIZE, SPACING } from '@/constants/Colors';
import Button from '@/components/ui/Button';

interface LoadErrorProps {
  /** 무엇을 못 불러왔는지. 기본은 일반 문구 */
  message?: string;
  onRetry: () => void;
}

/**
 * 목록·기록을 서버에서 못 불러왔을 때 보이는 안내 + 다시 시도.
 *
 * 이게 없으면 실패한 화면이 «데이터가 없는 화면» 과 똑같이 보인다 — 네트워크가 끊긴 사용자가
 * «모임이 없어요» «운동 기록이 없습니다» 를 보고 자기 기록이 사라졌다고 오해한다.
 */
export default function LoadError({ message = '불러오지 못했어요. 네트워크를 확인해주세요.', onRetry }: LoadErrorProps) {
  return (
    <View style={styles.wrap} accessibilityRole="alert">
      <Text style={styles.text}>{message}</Text>
      <Button title="다시 시도" onPress={onRetry} variant="outline" size="sm" />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { alignItems: 'center', gap: SPACING.sm, paddingVertical: SPACING.lg },
  text: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, textAlign: 'center' },
});
