import { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { WebView } from 'react-native-webview';
import { buildMannequinHtml, type MannequinExercise } from './mannequin/buildMannequinHtml';

interface Props {
  exercise: MannequinExercise;
}

/**
 * 3D 목각인형 시범 (폰). three.js 장면을 WebView 에 띄운다.
 * 카메라와 동시에 돌리면 폰이 버벅일 수 있어서, 화면 쪽에서 운동 중에는 아예 마운트하지 않는다.
 */
export default function MannequinDemo({ exercise }: Props) {
  const html = useMemo(() => buildMannequinHtml(exercise), [exercise]);
  return (
    <View style={styles.box}>
      <WebView
        key={exercise}
        originWhitelist={['*']}
        source={{ html }}
        style={styles.web}
        scrollEnabled={false}
        bounces={false}
        overScrollMode="never"
        setSupportMultipleWindows={false}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  box: { flex: 1, overflow: 'hidden' },
  web: { flex: 1, backgroundColor: 'transparent' },
});
