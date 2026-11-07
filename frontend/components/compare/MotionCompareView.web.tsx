import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef } from 'react';
import { StyleSheet, View } from 'react-native';
import { buildMannequinHtml } from '@/components/exercise/mannequin/buildMannequinHtml';
import { parseSceneMessage, type CompareViewHandle, type CompareViewProps } from './types';

/** 3D 운동 비교 장면 (웹). 같은 HTML 을 iframe 으로 띄우고, 장면의 전역 함수를 직접 부른다(srcDoc 는 같은 출처). */
const MotionCompareView = forwardRef<CompareViewHandle, CompareViewProps>(function MotionCompareView(
  { exercise, onReference, onPosition }, ref,
) {
  const html = useMemo(() => buildMannequinHtml(exercise, { compare: true }), [exercise]);
  const frame = useRef<HTMLIFrameElement>(null);
  const call = useCallback((fn: string, ...args: unknown[]) => {
    const w = frame.current?.contentWindow as unknown as Record<string, ((...a: unknown[]) => void) | undefined> | null;
    w?.[fn]?.(...args);
  }, []);

  useImperativeHandle(ref, () => ({
    setMotion: (payload) => call('__setMotion', payload),
    play: (on) => call('__play', on),
    seek: (frac) => call('__seek', frac),
    setMode: (mode) => call('__setMode', mode),
    setSpeed: (speed) => call('__speed', speed),
  }), [call]);

  useEffect(() => {
    const handler = (e: MessageEvent) => {
      if (e.source !== frame.current?.contentWindow || typeof e.data !== 'string') return;
      const m = parseSceneMessage(e.data);
      if (!m) return;
      if (m.type === 'reference') onReference(m.cycles);
      else onPosition(m.event);
    };
    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, [onReference, onPosition]);

  return (
    <View style={styles.box}>
      <iframe
        key={exercise}
        ref={frame}
        title="3D 운동 비교"
        srcDoc={html}
        style={{ border: 0, width: '100%', height: '100%', background: 'transparent' }}
      />
    </View>
  );
});

export default MotionCompareView;

const styles = StyleSheet.create({
  box: { flex: 1, overflow: 'hidden', borderRadius: 12 },
});
