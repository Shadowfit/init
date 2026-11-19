import { useEffect, useState } from 'react';
import { View, Text, StyleSheet, ScrollView, ActivityIndicator } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { Alert } from '@/utils/alert';
import { devWarn } from '@/utils/devLog';
import Button from '@/components/ui/Button';
import Checkbox from '@/components/ui/Checkbox';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import { consentService } from '@/services/consentService';
import { openLegalDocument } from '@/utils/legalLinks';
import { useAuthStore } from '@/stores/authStore';
import type { ConsentStatus, ConsentType } from '@/types/consent';

// 재동의 화면에서 다루는 문서 — 가입 필수 둘뿐이다. 건강정보 동의는 온보딩의 키·몸무게 단계에서 따로 받는다.
const DOCS: Partial<Record<ConsentType, { label: string; doc: 'terms' | 'privacy' }>> = {
  TERMS: { label: '이용약관에 동의합니다', doc: 'terms' },
  PRIVACY: { label: '개인정보 수집·이용에 동의합니다', doc: 'privacy' },
};

/**
 * 재동의 화면 — 가입 필수 약관 중 **현재 버전에 아직 동의하지 않은 것**이 있는 회원이 보는 화면.
 *
 * 두 경우가 여기로 온다: ① 동의 화면이 생기기 전에 가입한 회원 ② 약관이 개정돼 서버의 문서 버전이 오른 뒤의 회원.
 * 라우팅 가드(`_layout.tsx`)가 `consentMissing` 이 비어 있지 않으면 이 화면으로 보낸다.
 *
 * 동의한 버전은 **서버가 알려 준 현재 버전**(`current`)이다 — 앱 상수가 아니다. 앱이 옛 버전을 들고 있어도 서버 기준으로
 * 동의가 기록된다. 동의하지 않을 자유도 있어야 하므로 «동의하지 않고 로그아웃» 을 둔다(서비스를 쓰려면 필수 동의가 필요하다).
 */
export default function ConsentScreen() {
  const router = useRouter();
  const logout = useAuthStore((s) => s.logout);
  const refreshConsentStatus = useAuthStore((s) => s.refreshConsentStatus);

  const [status, setStatus] = useState<ConsentStatus | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [checked, setChecked] = useState<Partial<Record<ConsentType, boolean>>>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    consentService
      .getStatus()
      .then((res) => setStatus(res.data))
      .catch((e) => {
        devWarn('[consent] status 조회 실패', e?.response?.status);
        setLoadFailed(true);
      });
  }, []);

  const missing = (status?.missing ?? []).filter((t) => DOCS[t]);
  const allChecked = missing.length > 0 && missing.every((t) => checked[t]);

  const handleAgree = async () => {
    if (!status || !allChecked) return;
    setSubmitting(true);
    try {
      await consentService.record(missing.map((type) => ({ type, version: status.current[type] })));
      await refreshConsentStatus();
      // 가드는 로그인 화면에서만 메인으로 보낸다 — 여기서는 직접 이동한다. 온보딩이 안 끝났다면 가드가 이어서 온보딩으로 보낸다.
      router.replace('/(tabs)');
    } catch (e: any) {
      devWarn('[consent] 동의 기록 실패', e?.response?.status);
      Alert.alert(
        '동의를 저장하지 못했어요',
        e?.response?.data?.message ?? '잠시 후 다시 시도해주세요',
      );
      // 문서가 그 사이 개정됐다면(409) 최신 버전과 항목을 다시 불러온다
      if (e?.response?.status === 409) {
        consentService.getStatus().then((res) => setStatus(res.data)).catch(() => {});
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleDecline = async () => {
    await logout();
    router.replace('/(auth)/login');
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <Text style={styles.title}>약관에 동의해 주세요</Text>
        <Text style={styles.desc}>
          서비스를 계속 이용하려면 아래 약관에 대한 동의가 필요해요. 약관이 새로 생겼거나 개정된 경우예요.
        </Text>

        {!status && !loadFailed ? (
          <ActivityIndicator color={COLORS.primary} style={styles.loading} />
        ) : null}

        {loadFailed ? (
          <Text style={styles.error}>약관 정보를 불러오지 못했어요. 네트워크를 확인하고 다시 열어주세요.</Text>
        ) : null}

        {status && missing.length === 0 ? (
          <Text style={styles.desc}>동의할 항목이 없어요. 계속 진행할 수 있어요.</Text>
        ) : null}

        {missing.length > 0 ? (
          <View style={styles.box}>
            {missing.map((type) => (
              <Checkbox
                key={type}
                checked={!!checked[type]}
                onToggle={() => setChecked((c) => ({ ...c, [type]: !c[type] }))}
                label={DOCS[type]!.label}
                tag="필수"
                onView={() => openLegalDocument(DOCS[type]!.doc)}
              />
            ))}
          </View>
        ) : null}
      </ScrollView>

      <View style={styles.footer}>
        {status && missing.length === 0 ? (
          <Button title="계속하기" onPress={() => router.replace('/(tabs)')} />
        ) : (
          <Button
            title="동의하고 계속"
            onPress={handleAgree}
            disabled={!allChecked || submitting}
            loading={submitting}
          />
        )}
        <Text style={styles.decline} onPress={handleDecline} accessibilityRole="button">
          동의하지 않고 로그아웃
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  content: { padding: SPACING.xxl, paddingTop: SPACING.xxxl },
  title: { fontSize: FONT_SIZE.xxl, fontWeight: '700', color: COLORS.text, marginBottom: SPACING.md },
  desc: { fontSize: FONT_SIZE.md, color: COLORS.textSecondary, lineHeight: 22, marginBottom: SPACING.lg },
  loading: { marginTop: SPACING.xl },
  error: { fontSize: FONT_SIZE.sm, color: COLORS.error, marginTop: SPACING.md },
  box: {
    marginTop: SPACING.md,
    padding: SPACING.md,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    backgroundColor: COLORS.surface,
  },
  footer: { padding: SPACING.xxl, gap: SPACING.lg },
  decline: {
    textAlign: 'center',
    fontSize: FONT_SIZE.sm,
    color: COLORS.textMuted,
    textDecorationLine: 'underline',
    paddingVertical: SPACING.sm,
  },
});
