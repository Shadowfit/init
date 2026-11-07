import { View, Text, StyleSheet } from 'react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import type { LungeProgress, PoseSkipReason, SquatProgress } from '@/types/pose';

export type ReadinessTone = 'idle' | 'wait' | 'warn' | 'ready' | 'active' | 'error';

export interface Readiness {
  tone: ReadinessTone;
  title: string;
  detail?: string;
  /** 0: 운동 전, 1: 몸이 보이게 서기, 2: 똑바로 서기, 3: 준비 완료·운동 중 */
  step: 0 | 1 | 2 | 3;
}

interface ReadinessInput {
  isRecording: boolean;
  isLunge: boolean;
  /** AI 응답을 한 번이라도 받았는가 */
  gotResponse: boolean;
  /** AI 에 연결하지 못했다(네트워크/서버) */
  aiError: boolean;
  /** 가장 최근 «판정에 못 들어간» 사유. 정상 판정이 오면 null */
  lastSkip: PoseSkipReason | null;
  squat: SquatProgress | null;
  lunge: LungeProgress | null;
}

/**
 * 지금 사용자가 무엇을 해야 하는지를 AI 응답에서 계산한다 (2026-10-06).
 *
 * 우선순위: 연결 문제 > 사람/하체가 안 보임 > 스쿼트·런지 단계. 안 보이는 동안에는 단계 정보가
 * 낡았을 수 있어서(카운터가 마지막으로 본 값) «안 보임» 이 항상 먼저다.
 */
export function deriveReadiness(i: ReadinessInput): Readiness {
  if (!i.isRecording) {
    return { tone: 'idle', step: 0, title: '운동 시작 전', detail: '전신이 보이게 폰을 세워두고, 아래 버튼을 눌러 운동을 시작하세요.' };
  }
  if (i.aiError) {
    return { tone: 'error', step: 1, title: 'AI 서버 연결을 확인하고 있어요', detail: '같은 네트워크에 연결돼 있는지 확인해주세요.' };
  }
  if (!i.gotResponse) {
    return { tone: 'wait', step: 1, title: '카메라 연결 중…', detail: '잠시만 기다려주세요.' };
  }
  if (i.lastSkip === 'NO_POSE') {
    return { tone: 'warn', step: 1, title: '사람이 보이지 않아요', detail: '카메라 앞에 서서 머리부터 발끝까지 화면에 들어오게 해주세요.' };
  }
  if (i.lastSkip === 'LOW_VISIBILITY') {
    return { tone: 'warn', step: 1, title: '무릎·발목이 잘 안 보여요', detail: '뒤로 물러나 발끝까지 화면에 들어오게 서주세요.' };
  }
  if (i.isLunge) {
    if (i.lunge?.phase === 'returning' || i.lunge?.phase === 'descending') {
      return { tone: 'active', step: 3, title: '운동 중', detail: i.lunge.guidance };
    }
    return { tone: 'ready', step: 3, title: '준비 완료!', detail: '다리를 앞뒤로 벌리고 무릎을 굽혔다 펴세요.' };
  }
  switch (i.squat?.stage) {
    case 'ready':
      return { tone: 'ready', step: 3, title: '준비 완료!', detail: '이제 스쿼트를 시작하세요.' };
    case 'descending':
    case 'bottom':
      return { tone: 'active', step: 3, title: '운동 중', detail: i.squat.guidance };
    case 'waiting_for_standing':
      return { tone: 'wait', step: 2, title: '똑바로 서 주세요', detail: i.squat.guidance };
    default:
      return { tone: 'wait', step: 2, title: '자세를 확인하고 있어요', detail: '똑바로 선 채로 잠시 기다려주세요.' };
  }
}

const TONE_COLOR: Record<ReadinessTone, string> = {
  idle: 'rgba(255,255,255,0.35)',
  wait: COLORS.primary,
  warn: COLORS.warning,
  ready: COLORS.success,
  active: COLORS.primary,
  error: COLORS.error,
};

const STEP_LABELS = ['전신 보이기', '똑바로 서기', '운동 시작'];

export default function ReadinessBanner({ readiness }: { readiness: Readiness }) {
  const color = TONE_COLOR[readiness.tone];
  return (
    <View style={[styles.banner, { borderColor: color }]} accessibilityLiveRegion="polite">
      {readiness.step > 0 && (
        <View style={styles.steps}>
          {STEP_LABELS.map((label, index) => {
            const done = readiness.step >= index + 1;
            return (
              <View key={label} style={styles.step}>
                <View style={[styles.dot, done && { backgroundColor: color, borderColor: color }]}>
                  <Text style={[styles.dotText, done && styles.dotTextDone]}>{done && readiness.step > index + 1 ? '✓' : index + 1}</Text>
                </View>
                <Text style={[styles.stepLabel, done && { color: COLORS.text }]}>{label}</Text>
              </View>
            );
          })}
        </View>
      )}
      <Text style={[styles.title, { color }]}>{readiness.title}</Text>
      {!!readiness.detail && <Text style={styles.detail}>{readiness.detail}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    marginHorizontal: 20,
    marginTop: 12,
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: RADIUS.md,
    borderWidth: 2,
    backgroundColor: 'rgba(0,0,0,0.72)',
    gap: 6,
  },
  steps: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4 },
  step: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  dot: {
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 1.5,
    borderColor: 'rgba(255,255,255,0.35)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  dotText: { color: 'rgba(255,255,255,0.5)', fontSize: 12, fontWeight: '700' },
  dotTextDone: { color: COLORS.primaryText },
  stepLabel: { color: 'rgba(255,255,255,0.5)', fontSize: FONT_SIZE.xs, fontWeight: '600' },
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800' },
  detail: { color: COLORS.text, fontSize: FONT_SIZE.sm, lineHeight: 20 },
});
