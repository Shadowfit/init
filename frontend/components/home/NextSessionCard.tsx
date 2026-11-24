import { useCallback, useState } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useRouter, useFocusEffect } from 'expo-router';
import { Sparkles } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import LoadError from '@/components/ui/LoadError';
import { recommendationService } from '@/services/recommendationService';
import { toNextSessionView } from '@/utils/nextSession';
import { devWarn } from '@/utils/devLog';
import type { NextSessionRecommendation } from '@/types/recommendation';

interface Props {
  /** 로그인 상태에서만 부른다 — 추천은 회원 프로필과 완료 세션으로 계산한다 */
  enabled: boolean;
}

type Status = 'idle' | 'loading' | 'error' | 'ready';

/**
 * 홈의 «다음 스쿼트 추천» 카드 — GET /recommendations/next-session.
 *
 * 서버는 스쿼트 기준으로만 계산한다(종목 파라미터가 아직 없다). 그래서 카드도 «스쿼트» 로 못 박고,
 * 시작 버튼은 `exerciseId=1` 을 명시해 런지가 시작되지 않게 한다.
 *
 * 포커스마다 다시 받는다 — 운동을 끝내고 홈으로 돌아오면 최근 세션 평균이 바뀌어 추천도 바뀐다.
 * ⚠️ 서버의 규칙 임계값(상향 85% · 하향 60% 등)은 실사용자 데이터가 없어 잠정치다(recommendation-algorithm.md).
 *    그래서 화면은 «정답» 이 아니라 «추천» 으로 말하고, 근거 한 줄을 서버 문구 그대로 보여 준다.
 */
export default function NextSessionCard({ enabled }: Props) {
  const router = useRouter();
  const [status, setStatus] = useState<Status>('idle');
  const [rec, setRec] = useState<NextSessionRecommendation | null>(null);
  const [reload, setReload] = useState(0);

  useFocusEffect(
    useCallback(() => {
      if (!enabled) {
        setStatus('idle');
        setRec(null);
        return;
      }
      let cancelled = false;
      setStatus((prev) => (prev === 'ready' ? prev : 'loading')); // 이미 보이는 값은 지우지 않고 갱신한다
      recommendationService
        .getNextSession()
        .then((res) => {
          if (cancelled) return;
          setRec(res.data);
          setStatus('ready');
        })
        .catch((e) => {
          if (cancelled) return;
          devWarn('[next-session] status=', e?.response?.status);
          // 이미 받은 값이 있으면 그대로 두고, 처음부터 실패한 경우에만 오류 화면을 낸다.
          setStatus((prev) => (prev === 'ready' ? prev : 'error'));
        });
      return () => {
        cancelled = true;
      };
    }, [enabled, reload]),
  );

  if (!enabled || status === 'idle') return null;

  if (status === 'error') {
    return (
      <View style={styles.card}>
        <LoadError
          message="다음 스쿼트 추천을 불러오지 못했어요."
          onRetry={() => setReload((n) => n + 1)}
        />
      </View>
    );
  }

  if (status === 'loading' || !rec) {
    return (
      <View style={styles.card}>
        <Heading />
        <Text style={styles.reason}>추천을 불러오는 중...</Text>
      </View>
    );
  }

  const v = toNextSessionView(rec);
  return (
    <View style={styles.card}>
      <Heading />
      <View style={styles.statsRow}>
        <Stat label="난이도" value={v.level} />
        <Stat label="세트당 횟수" value={v.reps} />
        <Stat label="목표 싱크로율" value={v.sync} />
        <Stat label="세트 휴식" value={v.rest} />
      </View>
      {v.reason !== '' && <Text style={styles.reason}>{v.reason}</Text>}
      <Button
        title="스쿼트 시작하기"
        onPress={() => router.push({ pathname: '/(tabs)/exercise', params: { exerciseId: '1' } } as any)}
        size="sm"
      />
      <Text style={styles.footnote}>시작하면 세트당 횟수와 난이도가 이 값으로 정해져요. 추천 기준은 아직 다듬는 중이에요.</Text>
    </View>
  );
}

function Heading() {
  return (
    <View style={styles.heading}>
      <Sparkles size={20} color={COLORS.primary} strokeWidth={2} />
      <Text style={styles.eyebrow}>다음 스쿼트 추천</Text>
    </View>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.stat}>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    marginHorizontal: SPACING.xxl,
    marginBottom: SPACING.lg,
    padding: SPACING.xl,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    backgroundColor: COLORS.card,
    gap: SPACING.md,
  },
  heading: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm },
  eyebrow: { color: COLORS.primary, fontSize: FONT_SIZE.sm, fontWeight: '700' },
  statsRow: { flexDirection: 'row', gap: SPACING.sm },
  stat: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: SPACING.md,
    borderRadius: RADIUS.sm,
    backgroundColor: COLORS.surfaceLight,
  },
  statValue: { color: COLORS.text, fontSize: FONT_SIZE.md, fontWeight: '800' },
  statLabel: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, marginTop: 2 },
  reason: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 20 },
  footnote: { color: COLORS.textMuted, fontSize: FONT_SIZE.xs, lineHeight: 17 },
});
