import { useCallback, useState } from 'react';
import { View, Text, StyleSheet, FlatList, TouchableOpacity, ActivityIndicator, RefreshControl } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter, useFocusEffect } from 'expo-router';
import { ChevronLeft, BellRing, Heart } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import { notificationService } from '@/services/notificationService';
import type { Notification } from '@/types/social';

const PAGE_SIZE = 20;

// "2026-09-17T08:05:00" → "9.17 08:05" (오늘이면 "08:05")
function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const now = new Date();
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  return sameDay ? `${hh}:${mm}` : `${d.getMonth() + 1}.${d.getDate()} ${hh}:${mm}`;
}

// 보낸 사람이 탈퇴하면 sender* 가 전부 null — 화면은 «탈퇴한 회원»
function senderName(n: Notification): string {
  return n.senderUsername ?? '탈퇴한 회원';
}

function bodyText(n: Notification): string {
  if (n.type === 'CHEER') return n.message ?? '';
  return '오늘 운동을 재촉했어요 👊';
}

export default function NotificationsScreen() {
  const router = useRouter();
  const [items, setItems] = useState<Notification[]>([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async (nextPage: number, replace: boolean) => {
    if (loading) return;
    setLoading(true);
    try {
      const res = await notificationService.list(nextPage, PAGE_SIZE);
      const { content, totalPages } = res.data;
      setItems((prev) => (replace ? content : [...prev, ...content]));
      setPage(nextPage);
      setHasMore(nextPage + 1 < totalPages);
    } catch (e: any) {
      console.warn('[notifications] status=', e?.response?.status, 'data=', JSON.stringify(e?.response?.data));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [loading]);

  useFocusEffect(
    useCallback(() => {
      load(0, true);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []),
  );

  // 읽음은 낙관적으로 — 실패해도 다음 조회에서 서버 값으로 돌아온다
  const handlePress = async (n: Notification) => {
    if (n.read) return;
    setItems((prev) => prev.map((x) => (x.id === n.id ? { ...x, read: true } : x)));
    try {
      await notificationService.markRead(n.id);
    } catch {
      setItems((prev) => prev.map((x) => (x.id === n.id ? { ...x, read: false } : x)));
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => router.back()} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
          <ChevronLeft size={24} color={COLORS.text} strokeWidth={2} />
        </TouchableOpacity>
        <Text style={styles.title}>알림</Text>
        <View style={{ width: 24 }} />
      </View>

      <FlatList
        data={items}
        keyExtractor={(n) => String(n.id)}
        contentContainerStyle={styles.list}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={() => { setRefreshing(true); load(0, true); }}
            tintColor={COLORS.primary}
          />
        }
        onEndReachedThreshold={0.4}
        onEndReached={() => { if (hasMore && !loading) load(page + 1, false); }}
        ListEmptyComponent={
          loading ? (
            <ActivityIndicator color={COLORS.primary} style={{ marginTop: SPACING.xxxl }} />
          ) : (
            <View style={styles.empty}>
              <Text style={styles.emptyText}>아직 받은 알림이 없어요</Text>
              <Text style={styles.emptySub}>모임 친구가 재촉하거나 응원하면 여기에 쌓여요</Text>
            </View>
          )
        }
        ListFooterComponent={loading && items.length > 0 ? <ActivityIndicator color={COLORS.primary} style={{ marginVertical: SPACING.lg }} /> : null}
        renderItem={({ item: n }) => {
          const isCheer = n.type === 'CHEER';
          return (
            <TouchableOpacity
              style={[styles.card, !n.read && styles.cardUnread]}
              onPress={() => handlePress(n)}
              activeOpacity={0.8}
            >
              <View style={[styles.icon, isCheer ? styles.iconCheer : styles.iconNudge]}>
                {isCheer ? (
                  <Heart size={18} color={COLORS.primary} strokeWidth={2.25} />
                ) : (
                  <BellRing size={18} color={COLORS.black} strokeWidth={2.25} />
                )}
              </View>
              <View style={styles.body}>
                <View style={styles.bodyTop}>
                  <Text style={styles.sender} numberOfLines={1}>
                    {senderName(n)}
                    <Text style={styles.senderSuffix}>{isCheer ? '님의 응원' : '님의 재촉'}</Text>
                  </Text>
                  <Text style={styles.time}>{formatTime(n.createdAt)}</Text>
                </View>
                <Text style={[styles.message, isCheer && styles.messageCheer]}>{bodyText(n)}</Text>
              </View>
              {!n.read && <View style={styles.dot} />}
            </TouchableOpacity>
          );
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: SPACING.xl,
    paddingVertical: SPACING.md,
  },
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  list: { paddingHorizontal: SPACING.xl, paddingBottom: SPACING.xxxl, gap: SPACING.sm },
  empty: { alignItems: 'center', marginTop: SPACING.xxxl * 2, gap: SPACING.xs },
  emptyText: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.textSecondary },
  emptySub: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACING.md,
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
  },
  cardUnread: { borderColor: COLORS.primary },
  icon: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  iconNudge: { backgroundColor: COLORS.primary },
  iconCheer: { backgroundColor: COLORS.primaryDim },
  body: { flex: 1 },
  bodyTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: SPACING.sm },
  sender: { flex: 1, fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.text },
  senderSuffix: { fontWeight: '400', color: COLORS.textSecondary },
  time: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted },
  message: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, marginTop: 4 },
  messageCheer: { color: COLORS.text, fontSize: FONT_SIZE.md },
  dot: { width: 8, height: 8, borderRadius: 4, backgroundColor: COLORS.primary },
});
