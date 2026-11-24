import { useState, useCallback } from 'react';
import { View, Text, StyleSheet, ScrollView, TouchableOpacity, Switch } from 'react-native';
import { Alert } from '@/utils/alert';
import { SafeAreaView } from 'react-native-safe-area-context';
import Slider from '@react-native-community/slider';
import { useRouter, useFocusEffect } from 'expo-router';
import {
  ChevronLeft,
  ChevronRight,
  Dumbbell,
  Ruler,
  Target,
  UserCog,
  Megaphone,
  HelpCircle,
  LogOut,
  Pencil,
  Sprout,
  Crosshair,
  Apple,
  HeartPulse,
  Volume2,
  type LucideIcon,
} from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import { useAuthStore } from '@/stores/authStore';
import { queryClient, queryKeys, STALE } from '@/lib/queryClient';
import { memberService } from '@/services/memberService';
import { preferenceService } from '@/services/preferenceService';
import type { OnboardingResponse } from '@/types/user';
import type { TtsPreference } from '@/types/preference';

// 백엔드 WorkoutLevel: STARTER < BEGINNER < INTERMEDIATE < ADVANCED < EXPERT
const LEVEL_MAP: Record<string, { label: string; desc: string }> = {
  STARTER: { label: '입문', desc: '운동 자세, 운동 루틴 등 아무것도 몰라요' },
  BEGINNER: { label: '초급', desc: '자세는 조금 알지만 무슨 운동을 해야 할지 몰라요' },
  INTERMEDIATE: { label: '중급', desc: '운동 자세를 잘 알고, 나만의 루틴이 있어요' },
  ADVANCED: { label: '고급', desc: '운동을 직업으로 삼을 만큼의 지식이 있어요' },
  EXPERT: { label: '전문가', desc: '운동 선수급의 지식과 경험을 갖고 있어요' },
};

// 백엔드 SelectedPersona: BEGINNER, ADVANCED, DIET, REHAB
const PERSONA_MAP: Record<string, { Icon: LucideIcon; label: string; desc: string }> = {
  BEGINNER: { Icon: Sprout, label: '헬린이', desc: '친절하고 격려하는 초보 친화 코치' },
  ADVANCED: { Icon: Crosshair, label: 'FM 교관', desc: '엄격하고 체계적인 군대식 트레이너' },
  DIET: { Icon: Apple, label: '다이어터', desc: '체중 관리에 특화된 식단·운동 코치' },
  REHAB: { Icon: HeartPulse, label: '재활 전문', desc: '안전 최우선, 부상 방지 중심 가이드' },
};

export default function MyPageScreen() {
  const router = useRouter();
  const logout = useAuthStore((s) => s.logout);
  const deleteAccount = useAuthStore((s) => s.deleteAccount);
  const user = useAuthStore((s) => s.user);

  const [profile, setProfile] = useState<OnboardingResponse | null>(null);
  const [tts, setTts] = useState<TtsPreference | null>(null);

  // 화면 진입 / 재포커스 마다 조회하되 **5분 안에는 캐시를 쓴다** — 이 두 값은 이 앱 안에서 고칠 때만
  // 바뀐다. 고치는 곳이 캐시를 갱신·무효화한다(TTS 는 아래 핸들러가 setQueryData, 온보딩 저장은
  // `(onboarding)/index.tsx` 가 invalidate) 라서 «수정 후 돌아오면 갱신» 은 그대로 지켜진다.
  useFocusEffect(
    useCallback(() => {
      if (!user?.email) return;
      const email = user.email;
      queryClient
        .fetchQuery({
          queryKey: queryKeys.onboarding(email),
          queryFn: () => memberService.getOnboarding(email).then((res) => res.data),
          staleTime: STALE.profile,
        })
        .then(setProfile)
        .catch(() => {
          // 조회 실패 시 기존 표시 유지
        });
      queryClient
        .fetchQuery({
          queryKey: queryKeys.tts(email),
          queryFn: () => preferenceService.getTts().then((res) => res.data),
          staleTime: STALE.profile,
        })
        .then(setTts)
        .catch(() => {
          // 미설정 시 기본값 (백엔드가 ttsEnabled=true, ttsSpeed=1.0 으로 줄 것)
        });
    }, [user?.email]),
  );

  // TTS 토글: 즉시 서버 반영, 실패 시 롤백
  const handleTtsToggle = async (next: boolean) => {
    const prev = tts;
    setTts((cur) => (cur ? { ...cur, ttsEnabled: next } : { ttsEnabled: next, ttsSpeed: 1.0 }));
    try {
      const res = await preferenceService.updateTts({ ttsEnabled: next });
      setTts(res.data);
      if (user?.email) queryClient.setQueryData(queryKeys.tts(user.email), res.data);
    } catch (e: any) {
      setTts(prev);
      Alert.alert('TTS 설정 실패', e?.response?.data?.message ?? '잠시 후 다시 시도해주세요');
    }
  };

  // TTS 속도: 슬라이드 완료(release) 시점에만 서버 반영
  const handleTtsSpeedCommit = async (speed: number) => {
    const rounded = Math.round(speed * 10) / 10; // 0.1 단위
    const prev = tts;
    setTts((cur) => (cur ? { ...cur, ttsSpeed: rounded } : { ttsEnabled: true, ttsSpeed: rounded }));
    try {
      const res = await preferenceService.updateTts({ ttsSpeed: rounded });
      setTts(res.data);
      if (user?.email) queryClient.setQueryData(queryKeys.tts(user.email), res.data);
    } catch (e: any) {
      setTts(prev);
      Alert.alert('TTS 속도 변경 실패', e?.response?.data?.message ?? '잠시 후 다시 시도해주세요');
    }
  };

  const level = profile?.workoutLevel ? LEVEL_MAP[profile.workoutLevel] : null;
  const persona = profile?.selectedPersona ? PERSONA_MAP[profile.selectedPersona] : null;

  const handleLogout = () => {
    Alert.alert('로그아웃', '정말 로그아웃하시겠습니까?', [
      { text: '취소', style: 'cancel' },
      {
        text: '로그아웃',
        style: 'destructive',
        onPress: async () => {
          await logout();
          router.replace('/(auth)/login');
        },
      },
    ]);
  };

  const handleDeleteAccount = () => {
    Alert.alert(
      '회원 탈퇴',
      '탈퇴하면 계정과 운동 기록, 리포트, 목표, 작성한 모임 글이 모두 삭제되고 되돌릴 수 없어요.\n\n'
        + '모임의 그룹장이라면 다른 멤버에게 그룹장을 넘긴 뒤에 탈퇴할 수 있어요.',
      [
        { text: '취소', style: 'cancel' },
        {
          text: '탈퇴하기',
          style: 'destructive',
          onPress: async () => {
            try {
              await deleteAccount();
              router.replace('/(auth)/login');
            } catch (e: any) {
              // 서버 메시지가 그대로 사용자 문구다 — 운동 중이면 «운동을 마친 뒤», 그룹장이면 «양도한 뒤» 안내가 온다.
              Alert.alert(
                '탈퇴할 수 없어요',
                e?.response?.data?.message ?? '잠시 후 다시 시도해주세요',
              );
            }
          },
        },
      ],
    );
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false}>
        {/* 헤더 */}
        <View style={styles.header}>
          <TouchableOpacity onPress={() => router.back()}>
            <ChevronLeft size={20} color={COLORS.text} strokeWidth={2} />
          </TouchableOpacity>
          <Text style={styles.title}>마이페이지</Text>
          <View style={{ width: 20 }} />
        </View>

        {/* 프로필 카드 */}
        <View style={styles.profileCard}>
          <View style={styles.avatar}>
            <Dumbbell size={26} color={COLORS.primary} strokeWidth={2} />
          </View>
          <View>
            <Text style={styles.email}>{profile?.username || user?.email || ''}</Text>
            <Text style={styles.memberLabel}>{user?.email || 'ShadowFit 회원'}</Text>
          </View>
        </View>

        {/* 페르소나 설정 */}
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>페르소나 설정</Text>
          <TouchableOpacity
            onPress={() => router.push('/(onboarding)' as any)}
            style={styles.editBtnRow}
          >
            <Pencil size={12} color={COLORS.primary} strokeWidth={2} />
            <Text style={styles.editBtn}>수정</Text>
          </TouchableOpacity>
        </View>

        <InfoCard
          Icon={Dumbbell}
          label="운동 수준"
          title={level?.label ?? '미설정'}
          desc={level?.desc}
        />
        <InfoCard
          Icon={Ruler}
          label="키"
          title={profile?.height != null ? `${profile.height}cm` : '미설정'}
        />
        <InfoCard
          Icon={Target}
          label="목표 몸무게"
          title={profile?.weight != null ? `${profile.weight}kg` : '미설정'}
        />
        <InfoCard
          Icon={persona?.Icon ?? UserCog}
          label="트레이너 페르소나"
          title={persona ? persona.label : '미설정'}
          desc={persona?.desc}
        />

        {/* 음성 피드백 (TTS) 설정 */}
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>음성 피드백</Text>
          <View />
        </View>

        <View style={styles.ttsCard}>
          <View style={styles.ttsRow}>
            <View style={styles.ttsLabelRow}>
              <Volume2 size={18} color={COLORS.primary} strokeWidth={2} />
              <View>
                <Text style={styles.ttsTitle}>음성 안내</Text>
                <Text style={styles.ttsDesc}>운동 중 자세 교정 멘트를 음성으로 안내</Text>
              </View>
            </View>
            <Switch
              value={tts?.ttsEnabled ?? true}
              onValueChange={handleTtsToggle}
              trackColor={{ false: COLORS.surfaceLight, true: COLORS.primary }}
              thumbColor={COLORS.text}
            />
          </View>

          {(tts?.ttsEnabled ?? true) && (
            <View style={styles.ttsSpeedRow}>
              <View style={styles.ttsSpeedLabelRow}>
                <Text style={styles.ttsSpeedLabel}>속도</Text>
                <Text style={styles.ttsSpeedValue}>{(tts?.ttsSpeed ?? 1.0).toFixed(1)}x</Text>
              </View>
              <Slider
                style={styles.ttsSlider}
                minimumValue={0.5}
                maximumValue={2.0}
                step={0.1}
                value={tts?.ttsSpeed ?? 1.0}
                onSlidingComplete={handleTtsSpeedCommit}
                minimumTrackTintColor={COLORS.primary}
                maximumTrackTintColor={COLORS.surfaceLight}
                thumbTintColor={COLORS.primary}
              />
              <View style={styles.ttsSpeedScale}>
                <Text style={styles.ttsSpeedScaleText}>0.5x</Text>
                <Text style={styles.ttsSpeedScaleText}>2.0x</Text>
              </View>
            </View>
          )}
        </View>

        {/* 게시판 — 서버 API 가 없어 화면이 로컬 상태만 쓰는 목업이다(누구나 «공지» 를 써 올릴 수 있고 데이터가 저장되지 않는다).
            그대로 사용자에게 보이면 가짜 공지·가짜 점검 안내가 노출되므로 개발 빌드에서만 연다. 실서비스로 열려면 공지·Q&A API 가 먼저 필요하다. */}
        {__DEV__ && (
          <>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>게시판</Text>
            <View />
          </View>

          <TouchableOpacity
            style={styles.menuItem}
            activeOpacity={0.7}
            onPress={() => router.push('/board/notice' as any)}
          >
            <View style={styles.menuLeft}>
              <Megaphone size={18} color={COLORS.primary} strokeWidth={2} />
              <Text style={styles.menuText}>공지사항</Text>
            </View>
            <ChevronRight size={14} color={COLORS.textMuted} strokeWidth={2} />
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.menuItem}
            activeOpacity={0.7}
            onPress={() => router.push('/board/qna' as any)}
          >
            <View style={styles.menuLeft}>
              <HelpCircle size={18} color={COLORS.primary} strokeWidth={2} />
              <Text style={styles.menuText}>Q&A</Text>
            </View>
            <ChevronRight size={14} color={COLORS.textMuted} strokeWidth={2} />
          </TouchableOpacity>

          </>
        )}

        {/* 로그아웃 */}
        <TouchableOpacity style={styles.logoutBtn} onPress={handleLogout} activeOpacity={0.7}>
          <LogOut size={16} color={COLORS.textSecondary} strokeWidth={2} />
          <Text style={styles.logoutText}>로그아웃</Text>
        </TouchableOpacity>

        {/* 회원 탈퇴 — 로그아웃보다 눈에 덜 띄게, 하지만 앱 안에서 찾을 수 있게 */}
        <TouchableOpacity style={styles.deleteBtn} onPress={handleDeleteAccount} activeOpacity={0.7}>
          <Text style={styles.deleteText}>회원 탈퇴</Text>
        </TouchableOpacity>

        <View style={{ height: 100 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

function InfoCard({ Icon, emoji, label, title, desc }: {
  Icon?: LucideIcon;
  emoji?: string;
  label: string;
  title: string;
  desc?: string;
}) {
  return (
    <View style={styles.infoCard}>
      {Icon ? (
        <Icon size={18} color={COLORS.primary} strokeWidth={2} />
      ) : emoji ? (
        <Text style={styles.infoEmoji}>{emoji}</Text>
      ) : null}
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={styles.infoTitle}>{title}</Text>
      {desc && <Text style={styles.infoDesc}>{desc}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
    paddingVertical: SPACING.md,
  },
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },

  profileCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.lg,
    backgroundColor: COLORS.card,
    marginHorizontal: SPACING.xxl,
    marginTop: SPACING.md,
    padding: SPACING.xl,
    borderRadius: RADIUS.lg,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  avatar: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: COLORS.primaryDim,
    justifyContent: 'center',
    alignItems: 'center',
  },
  avatarIcon: { fontSize: 24 },
  editBtnRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  infoEmoji: { fontSize: 18 },

  // TTS 카드
  ttsCard: {
    backgroundColor: COLORS.card,
    marginHorizontal: SPACING.xxl,
    marginBottom: SPACING.md,
    padding: SPACING.lg,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  ttsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  ttsLabelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.md,
    flex: 1,
  },
  ttsTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  ttsDesc: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },
  ttsSpeedRow: {
    marginTop: SPACING.lg,
    paddingTop: SPACING.lg,
    borderTopWidth: 1,
    borderTopColor: COLORS.cardBorder,
  },
  ttsSpeedLabelRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: SPACING.sm,
  },
  ttsSpeedLabel: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, fontWeight: '600' },
  ttsSpeedValue: { fontSize: FONT_SIZE.sm, color: COLORS.primary, fontWeight: '800' },
  ttsSlider: { width: '100%', height: 32 },
  ttsSpeedScale: { flexDirection: 'row', justifyContent: 'space-between' },
  ttsSpeedScaleText: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  email: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  memberLabel: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, marginTop: 2 },

  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
    marginTop: SPACING.xxl,
    marginBottom: SPACING.md,
  },
  sectionTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.textSecondary },
  editBtn: { fontSize: FONT_SIZE.sm, color: COLORS.primary },

  infoCard: {
    backgroundColor: COLORS.card,
    marginHorizontal: SPACING.xxl,
    marginBottom: SPACING.md,
    padding: SPACING.lg,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  infoIcon: { marginBottom: 4 },
  infoLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted, marginBottom: 4 },
  infoTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  infoDesc: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, marginTop: 2 },

  menuItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.card,
    marginHorizontal: SPACING.xxl,
    marginBottom: SPACING.md,
    padding: SPACING.lg,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  menuLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.md,
  },
  menuText: {
    fontSize: FONT_SIZE.md,
    fontWeight: '600',
    color: COLORS.text,
  },

  logoutBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.sm,
    backgroundColor: COLORS.card,
    marginHorizontal: SPACING.xxl,
    marginTop: SPACING.xl,
    paddingVertical: SPACING.lg,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  logoutText: { fontSize: FONT_SIZE.md, color: COLORS.textSecondary },
  deleteBtn: {
    alignItems: 'center',
    marginTop: SPACING.lg,
    paddingVertical: SPACING.md,
  },
  deleteText: {
    fontSize: FONT_SIZE.sm,
    color: COLORS.textMuted,
    textDecorationLine: 'underline',
  },
});
