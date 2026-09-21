import { useCallback, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Modal,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { Alert } from '@/utils/alert';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter, useFocusEffect } from 'expo-router';
import { Users, Plus, KeyRound, ChevronRight, Mail, X } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import Input from '@/components/ui/Input';
import { groupService } from '@/services/groupService';
import type { Group, Invitation } from '@/types/social';

type Sheet = 'create' | 'join' | null;

// 에러 응답은 {status, message, timestamp} — code 필드가 없어 서버 message(한국어)를 그대로 보여준다
function errorText(e: any, fallback: string): string {
  return e?.response?.data?.message ?? fallback;
}

export default function GroupsScreen() {
  const router = useRouter();
  const [groups, setGroups] = useState<Group[]>([]);
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [loading, setLoading] = useState(true);

  const [sheet, setSheet] = useState<Sheet>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(() => {
    Promise.all([groupService.listMine(), groupService.listMyInvitations()])
      .then(([g, inv]) => {
        setGroups(g.data);
        setInvitations(inv.data.filter((i) => i.status === 'PENDING'));
      })
      .catch((e) => console.warn('[groups] status=', e?.response?.status, 'data=', JSON.stringify(e?.response?.data)))
      .finally(() => setLoading(false));
  }, []);

  useFocusEffect(useCallback(() => { load(); }, [load]));

  const closeSheet = () => {
    setSheet(null);
    setName('');
    setDescription('');
    setCode('');
  };

  const handleCreate = async () => {
    if (!name.trim()) {
      Alert.alert('알림', '모임 이름을 입력해주세요');
      return;
    }
    setSubmitting(true);
    try {
      const res = await groupService.create({ name: name.trim(), description: description.trim() || undefined });
      closeSheet();
      Alert.alert('모임 생성 완료', `초대 코드: ${res.data.inviteCode}\n친구에게 코드를 알려주면 바로 참여할 수 있어요.`);
      router.push(`/group/${res.data.id}` as any);
    } catch (e: any) {
      Alert.alert('생성 실패', errorText(e, '모임을 만들지 못했어요. 잠시 후 다시 시도해주세요.'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleJoin = async () => {
    if (!code.trim()) {
      Alert.alert('알림', '초대 코드를 입력해주세요');
      return;
    }
    setSubmitting(true);
    try {
      const res = await groupService.join(code.trim());
      closeSheet();
      router.push(`/group/${res.data.id}` as any);
    } catch (e: any) {
      Alert.alert('참여 실패', errorText(e, '모임에 참여하지 못했어요. 잠시 후 다시 시도해주세요.'));
    } finally {
      setSubmitting(false);
    }
  };

  const respondInvitation = async (inv: Invitation, accept: boolean) => {
    try {
      if (accept) await groupService.acceptInvitation(inv.id);
      else await groupService.declineInvitation(inv.id);
      load();
    } catch (e: any) {
      Alert.alert('처리 실패', errorText(e, '초대를 처리하지 못했어요.'));
      load();
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={styles.scroll}>
        <View style={styles.header}>
          <Text style={styles.title}>모임</Text>
          <Text style={styles.subtitle}>친구들과 같이 운동하고 서로 응원해요</Text>
        </View>

        {invitations.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>받은 초대</Text>
            {invitations.map((inv) => (
              <View key={inv.id} style={styles.inviteCard}>
                <Mail size={18} color={COLORS.primary} strokeWidth={2} />
                <Text style={styles.inviteText} numberOfLines={1}>
                  <Text style={styles.inviteGroup}>{inv.groupName}</Text> 모임에 초대받았어요
                </Text>
                <TouchableOpacity style={styles.inviteBtn} onPress={() => respondInvitation(inv, true)}>
                  <Text style={styles.inviteBtnText}>수락</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => respondInvitation(inv, false)} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
                  <X size={18} color={COLORS.textMuted} strokeWidth={2} />
                </TouchableOpacity>
              </View>
            ))}
          </View>
        )}

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>내 모임</Text>
          {loading ? (
            <ActivityIndicator color={COLORS.primary} style={{ marginVertical: SPACING.xl }} />
          ) : groups.length === 0 ? (
            <View style={styles.emptyCard}>
              <Users size={28} color={COLORS.textMuted} strokeWidth={1.5} />
              <Text style={styles.emptyTitle}>아직 참여한 모임이 없어요</Text>
              <Text style={styles.emptySub}>모임을 만들거나 친구의 초대 코드로 참여해보세요</Text>
            </View>
          ) : (
            groups.map((g) => (
              <TouchableOpacity
                key={g.id}
                style={styles.groupCard}
                activeOpacity={0.8}
                onPress={() => router.push(`/group/${g.id}` as any)}
              >
                <View style={styles.groupIcon}>
                  <Users size={20} color={COLORS.primary} strokeWidth={2} />
                </View>
                <View style={styles.groupInfo}>
                  <Text style={styles.groupName} numberOfLines={1}>{g.name}</Text>
                  {!!g.description && <Text style={styles.groupDesc} numberOfLines={1}>{g.description}</Text>}
                </View>
                <ChevronRight size={18} color={COLORS.textMuted} strokeWidth={2} />
              </TouchableOpacity>
            ))
          )}
        </View>

        <View style={styles.actions}>
          <Button
            title="모임 만들기"
            icon={<Plus size={18} color={COLORS.black} strokeWidth={2.5} />}
            onPress={() => setSheet('create')}
          />
          <Button
            title="초대 코드로 참여"
            variant="outline"
            icon={<KeyRound size={18} color={COLORS.primary} strokeWidth={2.5} />}
            onPress={() => setSheet('join')}
          />
        </View>
        <View style={{ height: 80 }} />
      </ScrollView>

      {/* 만들기 / 코드 참여 시트 */}
      <Modal visible={sheet !== null} transparent animationType="fade" onRequestClose={closeSheet}>
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.backdrop}>
          <View style={styles.sheet}>
            <View style={styles.sheetHeader}>
              <Text style={styles.sheetTitle}>{sheet === 'create' ? '모임 만들기' : '초대 코드로 참여'}</Text>
              <TouchableOpacity onPress={closeSheet} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
                <X size={22} color={COLORS.textSecondary} strokeWidth={2} />
              </TouchableOpacity>
            </View>

            {sheet === 'create' ? (
              <>
                <Input label="모임 이름" placeholder="예: 우리 진짜 거북목 되지 말자" value={name} onChangeText={setName} maxLength={50} containerStyle={styles.field} />
                <Input label="한 줄 소개 (선택)" placeholder="모임을 소개해주세요" value={description} onChangeText={setDescription} maxLength={200} containerStyle={styles.field} />
              </>
            ) : (
              <Input
                label="초대 코드"
                placeholder="8자리 코드"
                value={code}
                onChangeText={setCode}
                autoCapitalize="characters"
                autoCorrect={false}
                maxLength={12}
                containerStyle={styles.field}
              />
            )}

            {/* §3-G 가입 = 동의 고지 — 같은 모임 사람에게 출석 여부·연속일수가 보인다 */}
            <Text style={styles.notice}>
              참여하면 모임 사람들에게 내 출석 여부와 연속 운동일수가 보여요. 자세 점수·운동 상세는 공개되지 않아요.
            </Text>

            <Button
              title={sheet === 'create' ? '만들기' : '참여하기'}
              loading={submitting}
              onPress={sheet === 'create' ? handleCreate : handleJoin}
            />
          </View>
        </KeyboardAvoidingView>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  scroll: { paddingBottom: SPACING.xxxl },
  header: { paddingHorizontal: SPACING.xxl, paddingVertical: SPACING.md },
  title: { fontSize: FONT_SIZE.xl, fontWeight: '800', color: COLORS.text },
  subtitle: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },
  section: { paddingHorizontal: SPACING.xxl, marginTop: SPACING.lg },
  sectionTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text, marginBottom: SPACING.sm },

  inviteCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.sm,
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.primary,
    padding: SPACING.md,
    marginBottom: SPACING.sm,
  },
  inviteText: { flex: 1, fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },
  inviteGroup: { fontWeight: '700', color: COLORS.text },
  inviteBtn: { backgroundColor: COLORS.primary, borderRadius: RADIUS.full, paddingHorizontal: SPACING.md, paddingVertical: 6 },
  inviteBtnText: { fontSize: FONT_SIZE.xs, fontWeight: '700', color: COLORS.black },

  emptyCard: {
    alignItems: 'center',
    gap: SPACING.xs,
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    paddingVertical: SPACING.xxl,
  },
  emptyTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.textSecondary, marginTop: SPACING.xs },
  emptySub: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },

  groupCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.md,
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.sm,
  },
  groupIcon: { width: 40, height: 40, borderRadius: 20, backgroundColor: COLORS.primaryDim, alignItems: 'center', justifyContent: 'center' },
  groupInfo: { flex: 1 },
  groupName: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  groupDesc: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  actions: { paddingHorizontal: SPACING.xxl, marginTop: SPACING.xl, gap: SPACING.sm },

  backdrop: { flex: 1, backgroundColor: COLORS.overlay, justifyContent: 'center', paddingHorizontal: SPACING.xl },
  sheet: { backgroundColor: COLORS.surface, borderRadius: RADIUS.xl, borderWidth: 1, borderColor: COLORS.cardBorder, padding: SPACING.xl },
  sheetHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: SPACING.lg },
  sheetTitle: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  field: { marginBottom: SPACING.md },
  notice: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted, lineHeight: 18, marginBottom: SPACING.lg },
});
