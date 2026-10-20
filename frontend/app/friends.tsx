import { useCallback, useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, ScrollView, StyleSheet, Platform, Share } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect, useRouter } from 'expo-router';
import { ArrowLeft, Check, Copy, Users } from 'lucide-react-native';
import Button from '@/components/ui/Button';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';
import { socialService } from '@/services/socialService';
import { useAuthStore } from '@/stores/authStore';
import type { FriendAttendance, WorkoutGroup } from '@/types/social';

export default function FriendsScreen() {
  const router = useRouter();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const [friends, setFriends] = useState<FriendAttendance[]>([]);
  const [groups, setGroups] = useState<WorkoutGroup[]>([]);
  const [groupName, setGroupName] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [groupResponse, friendResponse] = await Promise.all([
        socialService.getMyGroups(),
        socialService.getFriends(),
      ]);
      setGroups(groupResponse.data);
      setFriends(friendResponse.data);
    } catch {
      setMessage('친구 현황을 불러오지 못했습니다. 연결을 확인하고 다시 시도해주세요.');
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(useCallback(() => {
    if (isAuthenticated) void refresh();
  }, [isAuthenticated, refresh]));

  const createGroup = async () => {
    const name = groupName.trim();
    if (!name) {
      setMessage('모임 이름을 입력해주세요.');
      return;
    }
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      await socialService.createGroup(name);
      setGroupName('');
      await refresh();
      setMessage('모임을 만들었습니다. 초대 코드를 친구에게 보내세요.');
    } catch (error: any) {
      setMessage(error?.response?.data?.message ?? '모임을 만들지 못했습니다. 다시 시도해주세요.');
    } finally {
      setBusy(false);
    }
  };

  const joinGroup = async () => {
    const code = inviteCode.trim();
    if (!code) {
      setMessage('초대 코드를 입력해주세요.');
      return;
    }
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      await socialService.joinGroup(code);
      setInviteCode('');
      await refresh();
      setMessage('모임에 참여했습니다.');
    } catch (error: any) {
      setMessage(error?.response?.data?.message ?? '초대 코드를 확인하고 다시 시도해주세요.');
    } finally {
      setBusy(false);
    }
  };

  const shareCode = async (group: WorkoutGroup) => {
    try {
      if (Platform.OS === 'web') {
        await navigator.clipboard.writeText(group.inviteCode);
        setMessage(`${group.name} 초대 코드를 복사했습니다.`);
      } else {
        await Share.share({ message: `ShadowFit ${group.name} 모임 초대 코드: ${group.inviteCode}` });
      }
    } catch {
      setMessage(`초대 코드: ${group.inviteCode}`);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.header}>
          <TouchableOpacity accessibilityLabel="뒤로 가기" onPress={() => router.canGoBack() ? router.back() : router.replace('/(tabs)')} style={styles.backButton}>
            <ArrowLeft size={22} color={COLORS.text} />
          </TouchableOpacity>
          <Text style={styles.title}>친구와 함께</Text>
        </View>
        <Text style={styles.intro}>서로의 운동 완료를 확인하고, 내 페이스로 주간 목표를 이어가요.</Text>
        <Text style={styles.privacy}>이 화면에는 친구의 오늘 운동 완료 여부만 보여요. 영상과 자세 점수는 공유하지 않아요.</Text>

        {!isAuthenticated ? (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>로그인하고 모임 시작하기</Text>
            <Button title="로그인" onPress={() => router.push('/(auth)/login')} style={styles.action} />
          </View>
        ) : (
          <>
            {message ? <Text style={styles.message}>{message}</Text> : null}
            <View style={styles.sectionHeading}>
              <Users size={19} color={COLORS.primary} />
              <Text style={[styles.sectionTitle, styles.sectionHeadingTitle]}>친구의 오늘</Text>
              <TouchableOpacity accessibilityRole="button" onPress={() => void refresh()}>
                <Text style={styles.link}>새로고침</Text>
              </TouchableOpacity>
            </View>
            {loading ? <Text style={styles.muted}>불러오는 중...</Text> : friends.length === 0 ? (
              <Text style={styles.muted}>아직 함께 운동하는 친구가 없습니다. 아래에서 모임을 만들어 초대해보세요.</Text>
            ) : friends.map((friend) => (
              <View key={friend.memberId} style={styles.friendRow}>
                <View style={styles.avatar}><Text style={styles.avatarText}>{friend.username.slice(0, 1)}</Text></View>
                <Text style={styles.friendName}>{friend.username}</Text>
                <View style={[styles.statusPill, friend.attendedToday && styles.statusDone]}>
                  {friend.attendedToday && <Check size={13} color={COLORS.black} />}
                  <Text style={[styles.statusText, friend.attendedToday && styles.statusDoneText]}>
                    {friend.attendedToday ? '오늘 완료' : '오늘 기록 전'}
                  </Text>
                </View>
              </View>
            ))}

            <Text style={styles.sectionTitle}>내 모임</Text>
            {groups.length === 0 ? <Text style={styles.muted}>참여 중인 모임이 없습니다.</Text> : groups.map((group) => (
              <View key={group.id} style={styles.card}>
                <Text style={styles.cardTitle}>{group.name}</Text>
                <Text style={styles.codeLabel}>친구 초대 코드</Text>
                <View style={styles.codeRow}>
                  <Text selectable style={styles.inviteCode}>{group.inviteCode}</Text>
                  <TouchableOpacity accessibilityRole="button" accessibilityLabel="초대 코드 공유" onPress={() => void shareCode(group)}>
                    <Copy size={20} color={COLORS.primary} />
                  </TouchableOpacity>
                </View>
              </View>
            ))}

            <View style={styles.card}>
              <Text style={styles.cardTitle}>새 모임 만들기</Text>
              <TextInput
                style={styles.input}
                placeholder="모임 이름"
                placeholderTextColor={COLORS.textMuted}
                value={groupName}
                onChangeText={setGroupName}
                maxLength={40}
              />
              <Button title="모임 만들기" onPress={() => void createGroup()} disabled={busy} style={styles.action} />
            </View>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>초대 코드로 참여하기</Text>
              <TextInput
                style={styles.input}
                placeholder="초대 코드 8자리"
                placeholderTextColor={COLORS.textMuted}
                value={inviteCode}
                onChangeText={setInviteCode}
                autoCapitalize="characters"
                maxLength={8}
              />
              <Button title="모임 참여" onPress={() => void joinGroup()} disabled={busy} style={styles.action} />
            </View>
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  content: { paddingHorizontal: SPACING.xxl, paddingBottom: SPACING.xxxl },
  header: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, paddingTop: SPACING.lg },
  backButton: { padding: SPACING.xs },
  title: { color: COLORS.text, fontSize: FONT_SIZE.xxl, fontWeight: '800' },
  intro: { color: COLORS.text, fontSize: FONT_SIZE.md, lineHeight: 23, marginTop: SPACING.xl },
  privacy: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 19, marginTop: SPACING.sm, marginBottom: SPACING.xl },
  sectionHeading: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm, marginBottom: SPACING.md },
  sectionTitle: { color: COLORS.text, fontSize: FONT_SIZE.lg, fontWeight: '700', marginTop: SPACING.lg, marginBottom: SPACING.md },
  sectionHeadingTitle: { flex: 1, marginTop: 0, marginBottom: 0 },
  link: { color: COLORS.primary, fontSize: FONT_SIZE.sm, fontWeight: '700' },
  muted: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, lineHeight: 20, marginBottom: SPACING.lg },
  message: { color: COLORS.primary, fontSize: FONT_SIZE.sm, lineHeight: 20, marginBottom: SPACING.md },
  friendRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md, paddingVertical: SPACING.sm },
  avatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: COLORS.surfaceLight, alignItems: 'center', justifyContent: 'center' },
  avatarText: { color: COLORS.text, fontWeight: '700' },
  friendName: { color: COLORS.text, fontSize: FONT_SIZE.md, flex: 1 },
  statusPill: { flexDirection: 'row', alignItems: 'center', gap: 3, paddingHorizontal: SPACING.sm, paddingVertical: SPACING.xs, backgroundColor: COLORS.surfaceLight, borderRadius: RADIUS.full },
  statusDone: { backgroundColor: COLORS.primary },
  statusText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, fontWeight: '700' },
  statusDoneText: { color: COLORS.black },
  card: { backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder, borderRadius: RADIUS.md, padding: SPACING.lg, marginBottom: SPACING.md },
  cardTitle: { color: COLORS.text, fontSize: FONT_SIZE.md, fontWeight: '700' },
  codeLabel: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs, marginTop: SPACING.md },
  codeRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: SPACING.xs },
  inviteCode: { color: COLORS.primary, fontSize: FONT_SIZE.xl, fontWeight: '800', letterSpacing: 2 },
  input: { color: COLORS.text, backgroundColor: COLORS.surfaceLight, borderRadius: RADIUS.sm, marginTop: SPACING.md, paddingHorizontal: SPACING.md, height: 46 },
  action: { marginTop: SPACING.md },
});
