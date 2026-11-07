import { forwardRef, useCallback, useImperativeHandle, useMemo, useRef } from 'react';
import { StyleSheet, View } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import { buildMannequinHtml } from '@/components/exercise/mannequin/buildMannequinHtml';
import { parseSceneMessage, type CompareViewHandle, type CompareViewProps } from './types';

/**
 * 3D 운동 비교 장면 (폰). 기존 마네킹 시범 장면에 비교 확장(compareExt)을 켠 WebView 다.
 * 장면이 뜨면 기준 시범 관절을 뽑아 보내고(onReference), 앱이 비교 데이터를 주면 사용자 골격을 그린다.
 */
const MotionCompareView = forwardRef<CompareViewHandle, CompareViewProps>(function MotionCompareView(
  { exercise, onReference, onPosition }, ref,
) {
  const html = useMemo(() => buildMannequinHtml(exercise, { compare: true }), [exercise]);
  const web = useRef<WebView>(null);
  const run = useCallback((js: string) => web.current?.injectJavaScript(`try{${js}}catch(e){} true;`), []);

  useImperativeHandle(ref, () => ({
    setMotion: (payload) => run(`window.__setMotion(${JSON.stringify(payload)});`),
    play: (on) => run(`window.__play(${on ? 'true' : 'false'});`),
    seek: (frac) => run(`window.__seek(${Number.isFinite(frac) ? frac : 0});`),
    setMode: (mode) => run(`window.__setMode(${JSON.stringify(mode)});`),
    setSpeed: (speed) => run(`window.__speed(${Number.isFinite(speed) ? speed : 1});`),
  }), [run]);

  const onMessage = useCallback((e: WebViewMessageEvent) => {
    const m = parseSceneMessage(e.nativeEvent.data);
    if (!m) return;
    if (m.type === 'reference') onReference(m.cycles);
    else onPosition(m.event);
  }, [onReference, onPosition]);

  return (
    <View style={styles.box}>
      <WebView
        key={exercise}
        ref={web}
        originWhitelist={['*']}
        source={{ html }}
        style={styles.web}
        onMessage={onMessage}
        scrollEnabled={false}
        bounces={false}
        overScrollMode="never"
        setSupportMultipleWindows={false}
      />
    </View>
  );
});

export default MotionCompareView;

const styles = StyleSheet.create({
  box: { flex: 1, overflow: 'hidden', borderRadius: 12 },
  web: { flex: 1, backgroundColor: 'transparent' },
});
