import { useRef, useState } from 'react';
import { PanResponder, StyleSheet, View } from 'react-native';
import { COLORS } from '@/constants/Colors';
import type { SegmentId } from '@/lib/motion/types';

export const SEGMENT_COLOR: Record<SegmentId, string> = {
  start: '#3a3a3a', descend: '#5C6BFF', bottom: '#FF9800', ascend: '#4CAF50', end: '#3a3a3a',
};

interface Props {
  /** 0~1 현재 위치 */
  value: number;
  /** 프레임별 구간 — 막대 배경색으로 보여 준다 */
  segments: SegmentId[];
  onSeekStart: () => void;
  onSeek: (frac: number) => void;
}

/**
 * 재생 타임라인 — 누르거나 끌면 그 위치로 이동한다. 막대 색은 동작 구간(시작/내려가기/최저점/올라오기).
 * 외부 슬라이더 모듈 대신 직접 만들었다: 설치된 @react-native-community/slider(5.1.2) 가 Expo Go 가 싣고 있는
 * 네이티브 버전(5.0.1)과 달라 폰에서 어긋날 수 있기 때문이다.
 */
export default function TimelineBar({ value, segments, onSeekStart, onSeek }: Props) {
  const [width, setWidth] = useState(1);
  const widthRef = useRef(1);
  const handlers = useRef({ onSeekStart, onSeek });
  handlers.current = { onSeekStart, onSeek };
  const frac = (x: number) => Math.max(0, Math.min(1, x / Math.max(1, widthRef.current)));
  const pan = useRef(PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: () => true,
    onPanResponderGrant: (e) => { handlers.current.onSeekStart(); handlers.current.onSeek(frac(e.nativeEvent.locationX)); },
    onPanResponderMove: (e) => handlers.current.onSeek(frac(e.nativeEvent.locationX)),
  })).current;

  return (
    <View
      style={styles.hit}
      onLayout={(e) => { widthRef.current = e.nativeEvent.layout.width; setWidth(e.nativeEvent.layout.width); }}
      {...pan.panHandlers}
    >
      <View style={styles.track} pointerEvents="none">
        {segments.map((s, i) => (
          <View key={i} style={{ flex: 1, backgroundColor: SEGMENT_COLOR[s] }} />
        ))}
      </View>
      <View style={[styles.thumb, { left: Math.max(0, Math.min(width - 16, value * width - 8)) }]} pointerEvents="none" />
    </View>
  );
}

const styles = StyleSheet.create({
  hit: { height: 36, justifyContent: 'center' },
  track: { flexDirection: 'row', height: 8, borderRadius: 4, overflow: 'hidden', backgroundColor: COLORS.surfaceLight },
  thumb: { position: 'absolute', top: 10, width: 16, height: 16, borderRadius: 8, backgroundColor: COLORS.primary, borderWidth: 2, borderColor: COLORS.background },
});
