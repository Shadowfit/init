import { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { buildMannequinHtml, type MannequinExercise } from './mannequin/buildMannequinHtml';

interface Props {
  exercise: MannequinExercise;
}

/** 3D 목각인형 시범 (웹). react-native-webview 가 웹을 지원하지 않아 iframe 으로 같은 HTML 을 띄운다. */
export default function MannequinDemo({ exercise }: Props) {
  const html = useMemo(() => buildMannequinHtml(exercise), [exercise]);
  return (
    <View style={styles.box}>
      <iframe
        key={exercise}
        title="3D 시범"
        srcDoc={html}
        style={{ border: 0, width: '100%', height: '100%', background: 'transparent' }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  box: { flex: 1, overflow: 'hidden' },
});
