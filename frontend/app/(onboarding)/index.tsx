import { devError } from '@/utils/devLog';
import { useEffect, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  } from 'react-native';
import { Alert } from '@/utils/alert';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import Slider from '@react-native-community/slider';
import { useRouter } from 'expo-router';
import {
  ChevronLeft,
  ChevronRight,
  Dumbbell,
  Ruler,
  Target,
  UserCog,
  Sprout,
  Crosshair,
  Apple,
  HeartPulse,
  type LucideIcon,
} from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import Checkbox from '@/components/ui/Checkbox';
import { consentService } from '@/services/consentService';
import { CONSENT_VERSION } from '@/constants/consent';
import { openLegalDocument } from '@/utils/legalLinks';
import type {
  WorkoutLevel,
  SelectedPersona,
  OnboardingData,
} from '@/types/user';
import { memberService } from '@/services/memberService';
import { useAuthStore } from '@/stores/authStore';

const TOTAL_STEPS = 4; // 레벨·키·몸무게·페르소나 (기준 영상 선택은 #802 로 없어졌다)

export default function OnboardingScreen() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const userEmail = useAuthStore((s) => s.user?.email);
  const markOnboardingCompleted = useAuthStore((s) => s.markOnboardingCompleted);
  const [step, setStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  // 건강정보(키·몸무게) 수집·이용 동의 — 일반 가입 동의와 분리해 몸 정보를 받기 직전에 따로 받는다.
  const [healthConsent, setHealthConsent] = useState(false);
  // 백엔드 OnboardingRequestDto 와 키 이름 일치
  const [data, setData] = useState<OnboardingData>({
    workoutLevel: null,
    height: 170,
    weight: 70,
    persona: null,
  });

  // 마이페이지에서 수정 진입한 경우 기존 데이터로 초기값 채움
  useEffect(() => {
    if (!userEmail) return;
    memberService
      .getOnboarding(userEmail)
      .then((res) => {
        const existing = res.data;
        setData({
          workoutLevel: existing.workoutLevel ?? null,
          height: existing.height ?? 170,
          weight: existing.weight ?? 70,
          persona: existing.selectedPersona ?? null,
        });
      })
      .catch(() => {
        // 신규 가입자는 일부 필드가 null 일 수 있음 → 기본값 유지
      });
  }, [userEmail]);

  // 이미 건강정보에 동의한 회원(마이페이지에서 수정 진입 등)은 체크된 채로 시작한다.
  useEffect(() => {
    if (!userEmail) return;
    consentService
      .getStatus()
      .then((res) => {
        const { agreed, current } = res.data;
        if (agreed.some((a) => a.type === 'HEALTH_INFO' && a.version === current.HEALTH_INFO)) {
          setHealthConsent(true);
        }
      })
      .catch(() => {
        // 현황을 못 읽으면 체크 안 된 채로 둔다 — 사용자가 직접 동의하면 된다.
      });
  }, [userEmail]);

  const canNext = () => {
    switch (step) {
      case 0: return data.workoutLevel !== null;
      case 1: return healthConsent; // 키 (슬라이더, 항상 값 있음) — 건강정보 동의가 있어야 다음
      case 2: return true; // 몸무게 (슬라이더, 항상 값 있음)
      case 3: return data.persona !== null;
      default: return false;
    }
  };

  const handleNext = async () => {
    if (step < TOTAL_STEPS - 1) {
      setStep(step + 1);
      return;
    }

    // 마지막 step → 백엔드에 PATCH
    if (!userEmail) {
      Alert.alert('오류', '로그인 정보가 없습니다. 다시 로그인해주세요.');
      router.replace('/(auth)/login');
      return;
    }
    if (!data.workoutLevel || !data.persona) {
      Alert.alert('오류', '모든 항목을 선택해주세요.');
      return;
    }

    setSubmitting(true);
    try {
      // 키·몸무게를 보내기 전에 건강정보 동의를 기록한다(멱등 — 이미 했어도 안전하다). 서버가 동의를 강제하는 중이면
      // 이 기록이 없을 때 몸 정보 저장을 거절한다.
      await consentService.record([{ type: 'HEALTH_INFO', version: CONSENT_VERSION.HEALTH_INFO }]);
      await memberService.updateOnboarding(userEmail, {
        selectedPersona: data.persona,
        workoutLevel: data.workoutLevel,
        height: data.height,
        weight: data.weight,
      });
      markOnboardingCompleted(); // 가드가 (onboarding) 으로 다시 안 빠지게
      router.replace('/(tabs)');
    } catch (e: any) {
      devError('[onboarding patch] status=', e.response?.status, 'data=', e.response?.data);
      const msg = e.response?.data?.message || '온보딩 저장에 실패했습니다';
      Alert.alert(`온보딩 실패 (${e.response?.status ?? 'no-response'})`, msg);
    } finally {
      setSubmitting(false);
    }
  };

  const handleBack = () => {
    if (step > 0) setStep(step - 1);
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* 상단 프로그레스 바 + 뒤로가기 */}
      <View style={styles.header}>
        {step > 0 ? (
          <TouchableOpacity onPress={handleBack} style={styles.backBtn}>
            <ChevronLeft size={20} color={COLORS.text} strokeWidth={2} />
          </TouchableOpacity>
        ) : (
          <View style={styles.backBtn} />
        )}
        <View style={styles.progressBar}>
          {Array.from({ length: TOTAL_STEPS }).map((_, i) => (
            <View
              key={i}
              style={[styles.progressDot, i <= step && styles.progressDotActive]}
            />
          ))}
        </View>
        <Text style={styles.stepText}>{step + 1}/{TOTAL_STEPS}</Text>
      </View>

      <ScrollView
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {step === 0 && <StepLevel data={data} setData={setData} />}
        {step === 1 && (
          <StepHeight
            data={data}
            setData={setData}
            healthConsent={healthConsent}
            onHealthConsentChange={setHealthConsent}
          />
        )}
        {step === 2 && <StepWeight data={data} setData={setData} />}
        {step === 3 && <StepPersona data={data} setData={setData} />}
      </ScrollView>

      {/* 하단 버튼 */}
      <View style={[styles.footer, { paddingBottom: SPACING.lg + insets.bottom }]}>
        <Button
          title={step === TOTAL_STEPS - 1 ? '완료' : '다음  >'}
          onPress={handleNext}
          disabled={!canNext() || submitting}
          loading={submitting}
        />
      </View>
    </SafeAreaView>
  );
}

// ─── Step 1: 운동 수준 ──────────────────────────────
// 백엔드 WorkoutLevel: STARTER < BEGINNER < INTERMEDIATE < ADVANCED < EXPERT
const LEVELS: { key: WorkoutLevel; label: string; desc: string }[] = [
  { key: 'STARTER', label: '입문', desc: '운동 자세, 운동 루틴 등 아무것도 몰라요' },
  { key: 'BEGINNER', label: '초급', desc: '자세는 조금 알지만 무슨 운동을 해야 할지 몰라요' },
  { key: 'INTERMEDIATE', label: '중급', desc: '운동 자세를 잘 알고, 나만의 루틴이 있어요' },
  { key: 'ADVANCED', label: '고급', desc: '운동을 직업으로 삼을 만큼의 지식이 있어요' },
  { key: 'EXPERT', label: '전문가', desc: '운동 선수급의 지식과 경험을 갖고 있어요' },
];

function StepLevel({
  data,
  setData,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
}) {
  return (
    <View>
      <View style={styles.stepIconWrap}>
        <Dumbbell size={28} color={COLORS.primary} strokeWidth={2} />
      </View>
      <Text style={styles.stepTitle}>운동 수준이 어떻게 되시나요?</Text>
      <Text style={styles.stepDesc}>적절한 운동 추천에 필요해요! 외부에 공개되지 않아요.</Text>

      {LEVELS.map((level) => (
        <TouchableOpacity
          key={level.key}
          style={[
            styles.optionCard,
            data.workoutLevel === level.key && styles.optionCardActive,
          ]}
          onPress={() => setData({ ...data, workoutLevel: level.key })}
          activeOpacity={0.7}
        >
          <Text style={[styles.optionLabel, data.workoutLevel === level.key && styles.optionLabelActive]}>
            {level.label}
          </Text>
          <Text style={styles.optionDesc}>{level.desc}</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ─── Step 1: 키 ─────────────────────────────
// 백엔드 OnboardingRequestDto.height 와 매핑
function StepHeight({
  data,
  setData,
  healthConsent,
  onHealthConsentChange,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
  healthConsent: boolean;
  onHealthConsentChange: (v: boolean) => void;
}) {
  return (
    <View>
      <View style={styles.stepIconWrap}>
        <Ruler size={28} color={COLORS.primary} strokeWidth={2} />
      </View>
      <Text style={styles.stepTitle}>키가 어떻게 되시나요?</Text>
      <Text style={styles.stepDesc}>
        정확한 운동 강도와 칼로리 계산에 사용돼요. 외부에 공개되지 않아요.
      </Text>

      <Text style={styles.weightDisplay}>
        {data.height} <Text style={styles.weightUnit}>cm</Text>
      </Text>

      <Slider
        style={styles.slider}
        minimumValue={100}
        maximumValue={220}
        step={1}
        value={data.height}
        onValueChange={(v) => setData({ ...data, height: v })}
        minimumTrackTintColor={COLORS.primary}
        maximumTrackTintColor={COLORS.surfaceLight}
        thumbTintColor={COLORS.primary}
      />
      <View style={styles.sliderLabels}>
        <Text style={styles.sliderLabel}>100cm</Text>
        <Text style={styles.sliderLabel}>220cm</Text>
      </View>

      {/* 건강정보 동의 — 키·몸무게는 건강에 관한 정보라 일반 가입 동의와 분리해 받는다 */}
      <View style={styles.healthConsent}>
        <Checkbox
          checked={healthConsent}
          onToggle={() => onHealthConsentChange(!healthConsent)}
          label="키·몸무게 등 건강정보 수집·이용에 동의합니다"
          tag="필수"
          onView={() => openLegalDocument('privacy')}
        />
        <Text style={styles.healthConsentHint}>
          운동 강도와 칼로리 계산에만 쓰이고, 탈퇴하면 삭제돼요.
        </Text>
      </View>
    </View>
  );
}

// ─── Step 2: 몸무게 ─────────────────────────────
// 백엔드 OnboardingRequestDto.weight 와 매핑
function StepWeight({
  data,
  setData,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
}) {
  const getMotivation = (weight: number) => {
    if (weight < 50) return '가벼운 몸을 목표로! 유산소 중심으로 도와드릴게요.';
    if (weight < 70) return '도전적인 목표: 몸무게의 70%로 중량이 목표예요!';
    if (weight < 90) return '탄탄한 체격을 목표로! 근력 운동을 추천해요.';
    return '파워 빌더! 고중량 훈련 루틴을 구성해드릴게요.';
  };

  return (
    <View>
      <View style={styles.stepIconWrap}>
        <Target size={28} color={COLORS.primary} strokeWidth={2} />
      </View>
      <Text style={styles.stepTitle}>목표 몸무게가 어떻게 되시나요?</Text>

      <View style={styles.motivationBox}>
        <Text style={styles.motivationIcon}>🏆</Text>
        <Text style={styles.motivationText}>{getMotivation(data.weight)}</Text>
      </View>

      <Text style={styles.weightDisplay}>
        {data.weight} <Text style={styles.weightUnit}>kg</Text>
      </Text>

      <Slider
        style={styles.slider}
        minimumValue={30}
        maximumValue={150}
        step={1}
        value={data.weight}
        onValueChange={(v) => setData({ ...data, weight: v })}
        minimumTrackTintColor={COLORS.primary}
        maximumTrackTintColor={COLORS.surfaceLight}
        thumbTintColor={COLORS.primary}
      />
      <View style={styles.sliderLabels}>
        <Text style={styles.sliderLabel}>30kg</Text>
        <Text style={styles.sliderLabel}>150kg</Text>
      </View>
    </View>
  );
}

// ─── Step 3: 트레이너 페르소나 ───────────────────────
// 백엔드 SelectedPersona: BEGINNER, ADVANCED, DIET, REHAB
const PERSONAS: { key: SelectedPersona; Icon: LucideIcon; label: string; desc: string }[] = [
  { key: 'BEGINNER', Icon: Sprout, label: '헬린이', desc: '친절하고 격려하는 초보 친화 코치' },
  { key: 'ADVANCED', Icon: Crosshair, label: 'FM 교관', desc: '엄격하고 체계적인 군대식 트레이너' },
  { key: 'DIET', Icon: Apple, label: '다이어터', desc: '체중 관리에 특화된 식단·운동 코치' },
  { key: 'REHAB', Icon: HeartPulse, label: '재활 전문', desc: '안전 최우선, 부상 방지 중심 가이드' },
];

function StepPersona({
  data,
  setData,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
}) {
  return (
    <View>
      <View style={styles.stepIconWrap}>
        <UserCog size={28} color={COLORS.primary} strokeWidth={2} />
      </View>
      <Text style={styles.stepTitle}>어떤 트레이너 스타일을 원하시나요?</Text>
      <Text style={styles.stepDesc}>페르소나에 따라 피드백 방식이 달라집니다.</Text>

      {PERSONAS.map((p) => {
        const isActive = data.persona === p.key;
        return (
          <TouchableOpacity
            key={p.key}
            style={[styles.optionCard, isActive && styles.optionCardActive]}
            onPress={() => setData({ ...data, persona: p.key })}
            activeOpacity={0.7}
          >
            <View style={styles.personaRow}>
              <View style={styles.personaIconWrap}>
                <p.Icon
                  size={28}
                  color={isActive ? COLORS.primary : COLORS.textSecondary}
                  strokeWidth={2}
                />
              </View>
              <View>
                <Text style={[styles.optionLabel, isActive && styles.optionLabelActive]}>
                  {p.label}
                </Text>
                <Text style={styles.optionDesc}>{p.desc}</Text>
              </View>
            </View>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  scrollContent: { flexGrow: 1, paddingHorizontal: SPACING.xxl, paddingBottom: 100 },

  // Header
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
  },
  backBtn: { width: 32 },
  progressBar: { flex: 1, flexDirection: 'row', gap: 6, marginHorizontal: SPACING.md },
  progressDot: {
    flex: 1,
    height: 4,
    backgroundColor: COLORS.surfaceLight,
    borderRadius: 2,
  },
  progressDotActive: { backgroundColor: COLORS.primary },
  stepText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, width: 32, textAlign: 'right' },

  // Step common
  stepIcon: { fontSize: 28, marginTop: SPACING.xl, marginBottom: SPACING.md },
  stepIconWrap: { marginTop: SPACING.xl, marginBottom: SPACING.md },
  stepTitle: {
    fontSize: FONT_SIZE.xl,
    fontWeight: '800',
    color: COLORS.text,
    marginBottom: SPACING.sm,
  },
  stepDesc: {
    fontSize: FONT_SIZE.sm,
    color: COLORS.textSecondary,
    marginBottom: SPACING.xxl,
  },

  // Option cards
  optionCard: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.md,
  },
  optionCardActive: {
    borderColor: COLORS.primary,
    backgroundColor: COLORS.primaryDim,
  },
  optionLabel: {
    fontSize: FONT_SIZE.md,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 2,
  },
  optionLabelActive: { color: COLORS.primary },
  optionDesc: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },

  // Persona
  personaRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md },
  personaIconWrap: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: COLORS.surfaceLight,
    justifyContent: 'center',
    alignItems: 'center',
  },

  // Weight
  motivationBox: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    padding: SPACING.lg,
    marginBottom: SPACING.xxl,
    gap: SPACING.md,
  },
  motivationIcon: { fontSize: 24 },
  motivationText: { flex: 1, fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, lineHeight: 20 },
  weightDisplay: {
    fontSize: 56,
    fontWeight: '800',
    color: COLORS.text,
    textAlign: 'center',
    marginBottom: SPACING.lg,
  },
  weightUnit: { fontSize: FONT_SIZE.xl, fontWeight: '400', color: COLORS.textSecondary },
  slider: { width: '100%', height: 40 },
  sliderLabels: { flexDirection: 'row', justifyContent: 'space-between' },
  sliderLabel: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted },
  healthConsent: {
    marginTop: SPACING.xxl,
    padding: SPACING.md,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    backgroundColor: COLORS.surface,
  },
  healthConsentHint: {
    marginTop: SPACING.xs,
    fontSize: FONT_SIZE.xs,
    color: COLORS.textMuted,
  },

  // Footer
  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    paddingHorizontal: SPACING.xxl,
    paddingVertical: SPACING.lg,
    backgroundColor: COLORS.background,
  },
});
