import { useMemo } from 'react';
import { View } from 'react-native';
import Svg, { Line, Circle } from 'react-native-svg';
import { COLORS } from '@/constants/Colors';

/**
 * Worst 구간 대표 프레임의 자세를 다시 그리는 스켈레톤 (P5 Tier 0).
 *
 * 입력은 백엔드가 `pose_data.joint_coordinates` 를 그대로 실어 보내는 JSON 문자열이다.
 * 형식은 MediaPipe 33관절 `[{x, y, z, index, visibility}, ...]` (로컬 DB 실측으로 확인).
 * 파싱이 안 되거나 관절이 33개가 아니면 아무것도 그리지 않는다 — 부가 정보라 화면을 깨면 안 된다.
 */

// MediaPipe Pose 연결선 중 얼굴 세부(눈·입)를 뺀 몸통·팔·다리만.
const BONES: [number, number][] = [
  [11, 12], [11, 13], [13, 15], [12, 14], [14, 16],   // 어깨·팔
  [11, 23], [12, 24], [23, 24],                       // 몸통
  [23, 25], [25, 27], [24, 26], [26, 28],             // 다리
  [27, 29], [29, 31], [27, 31], [28, 30], [30, 32], [28, 32], // 발
];
const HEAD = 0; // 코 — 머리 위치 표시용 점 하나
const VISIBLE_MIN = 0.3;
// 리포트 응답에는 촬영 비율이 없다. x 는 너비 기준, y 는 높이 기준 정규화라 비율을 모르면 옆으로 퍼져 보인다.
// 세로 촬영(3:4)을 가정한다 — 근사치이므로 각도 판단용이 아니라 "어떤 자세였나" 확인용이다.
const ASSUMED_ASPECT = 0.75;

type Pt = { x: number; y: number; v: number };

function parse(json: string | null | undefined): Pt[] | null {
  if (!json) return null;
  try {
    const arr = JSON.parse(json);
    if (!Array.isArray(arr) || arr.length < 33) return null;
    const pts = arr.map((p: unknown): Pt | null => {
      // 실제 저장 형식(로컬 DB 실측): {x, y, z, index, visibility}. 튜플 [x,y,z,v] 도 받는다.
      if (p && typeof p === 'object' && !Array.isArray(p)) {
        const o = p as { x?: unknown; y?: unknown; visibility?: unknown };
        if (typeof o.x === 'number' && typeof o.y === 'number') {
          return { x: o.x, y: o.y, v: typeof o.visibility === 'number' ? o.visibility : 1 };
        }
        return null;
      }
      if (Array.isArray(p) && typeof p[0] === 'number' && typeof p[1] === 'number') {
        return { x: p[0], y: p[1], v: typeof p[3] === 'number' ? p[3] : 1 };
      }
      return null;
    });
    return pts.every(Boolean) ? (pts as Pt[]) : null;
  } catch {
    return null;
  }
}

interface Props {
  jointCoordinates: string | null | undefined;
  size?: number;
}

export default function PoseSnapshot({ jointCoordinates, size = 220 }: Props) {
  const drawn = useMemo(() => {
    const pts = parse(jointCoordinates);
    if (!pts) return null;

    const used = [HEAD, ...BONES.flat()].filter((i) => pts[i].v >= VISIBLE_MIN);
    if (used.length < 4) return null;

    // 보이는 관절의 바운딩 박스에 맞춰 중앙 정렬·균등 스케일.
    const xs = used.map((i) => pts[i].x * ASSUMED_ASPECT);
    const ys = used.map((i) => pts[i].y);
    const minX = Math.min(...xs), maxX = Math.max(...xs);
    const minY = Math.min(...ys), maxY = Math.max(...ys);
    const span = Math.max(maxX - minX, maxY - minY) || 1;
    const pad = size * 0.1;
    const scale = (size - pad * 2) / span;
    const offX = (size - (maxX - minX) * scale) / 2;
    const offY = (size - (maxY - minY) * scale) / 2;
    const px = (p: Pt) => ({
      x: offX + (p.x * ASSUMED_ASPECT - minX) * scale,
      y: offY + (p.y - minY) * scale,
    });

    return {
      bones: BONES.filter(([a, b]) => pts[a].v >= VISIBLE_MIN && pts[b].v >= VISIBLE_MIN)
        .map(([a, b]) => ({ a: px(pts[a]), b: px(pts[b]), key: `${a}-${b}` })),
      joints: BONES.flat()
        .filter((i, n, all) => all.indexOf(i) === n && pts[i].v >= VISIBLE_MIN)
        .map((i) => ({ ...px(pts[i]), key: i })),
      head: pts[HEAD].v >= VISIBLE_MIN ? px(pts[HEAD]) : null,
    };
  }, [jointCoordinates, size]);

  if (!drawn) return null;

  return (
    <View
      accessible
      accessibilityLabel="가장 낮게 점수가 나온 순간의 자세"
      style={{
        alignSelf: 'center',
        width: size,
        height: size,
        borderRadius: 12,
        backgroundColor: COLORS.background,
      }}
    >
      <Svg width={size} height={size}>
        {drawn.bones.map((b) => (
          <Line
            key={b.key}
            x1={b.a.x} y1={b.a.y} x2={b.b.x} y2={b.b.y}
            stroke={COLORS.warning} strokeWidth={3} strokeLinecap="round"
          />
        ))}
        {drawn.joints.map((j) => (
          <Circle key={j.key} cx={j.x} cy={j.y} r={4} fill={COLORS.text} />
        ))}
        {drawn.head && <Circle cx={drawn.head.x} cy={drawn.head.y} r={8} fill="none" stroke={COLORS.text} strokeWidth={2} />}
      </Svg>
    </View>
  );
}
