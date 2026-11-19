import { useEffect, useState } from 'react';
import {
  Modal,
  View,
  Text,
  TouchableOpacity,
  TextInput,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
  Image,
  ScrollView,
  ActivityIndicator,
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { X, ImagePlus, Check, Users } from 'lucide-react-native';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import { Alert } from '@/utils/alert';
import { groupService } from '@/services/groupService';
import type { Group } from '@/types/social';

const CAPTION_MAX = 200;

interface Props {
  visible: boolean;
  sessionId: number;
  defaultCaption: string;
  onClose: () => void;
  onShared?: (groupIds: number[]) => void;
}

type Photo = { uri: string; mimeType: string; fileName: string };

// 네이티브는 {uri,name,type} 객체를, 웹은 실제 Blob 을 넣어야 multipart 가 파일로 간다.
async function appendPhoto(form: FormData, photo: Photo) {
  if (Platform.OS === 'web') {
    const blob = await (await fetch(photo.uri)).blob();
    form.append('photo', blob, photo.fileName);
  } else {
    form.append('photo', { uri: photo.uri, name: photo.fileName, type: photo.mimeType } as any);
  }
}

/**
 * 운동 기록을 모임 피드에 공유하는 시트. 내 모임을 여러 개 고를 수 있고, 이 운동을 이미 올린 모임은
 * «공유됨» 으로 잠근다 — 서버도 같은 운동 · 같은 모임 재공유를 409 로 막는다.
 */
export default function ShareSessionSheet({ visible, sessionId, defaultCaption, onClose, onShared }: Props) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [shared, setShared] = useState<Set<number>>(new Set());
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [caption, setCaption] = useState('');
  const [photo, setPhoto] = useState<Photo | null>(null);
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (!visible) return;
    setCaption(defaultCaption);
    setPhoto(null);
    setSelected(new Set());
    setLoading(true);
    Promise.all([groupService.listMine(), groupService.getSharedGroupIds(sessionId)])
      .then(([g, s]) => {
        const done = new Set(s.data);
        setGroups(g.data);
        setShared(done);
        // 모임이 하나뿐이고 아직 안 올렸으면 미리 골라 둔다
        const open = g.data.filter((x) => !done.has(x.id));
        if (open.length === 1) setSelected(new Set([open[0].id]));
      })
      .catch((e) => Alert.alert('불러오기 실패', e?.response?.data?.message ?? '모임 목록을 불러오지 못했어요.'))
      .finally(() => setLoading(false));
  }, [visible, sessionId, defaultCaption]);

  const toggle = (id: number) => {
    if (shared.has(id)) return;
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const pickPhoto = async () => {
    const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('사진 권한', '사진을 고르려면 사진 보관함 접근을 허용해주세요.');
      return;
    }
    const r = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsEditing: true,
      aspect: [4, 3],
      quality: 0.7, // 1 미만이면 iOS 도 JPEG 로 다시 인코딩한다(HEIC 는 서버가 받지 않는다)
    });
    if (r.canceled || !r.assets?.[0]) return;
    const a = r.assets[0];
    const mime = a.mimeType ?? 'image/jpeg';
    const ext = mime.includes('png') ? 'png' : mime.includes('webp') ? 'webp' : 'jpg';
    setPhoto({ uri: a.uri, mimeType: mime, fileName: `photo.${ext}` });
  };

  const submit = async () => {
    if (selected.size === 0) {
      Alert.alert('공유하기', '공유할 모임을 골라주세요.');
      return;
    }
    setSending(true);
    const ok: number[] = [];
    const failed: string[] = [];
    for (const groupId of selected) {
      try {
        const form = new FormData();
        form.append('sessionId', String(sessionId));
        if (caption.trim()) form.append('caption', caption.trim());
        if (photo) await appendPhoto(form, photo);
        await groupService.shareSession(groupId, form);
        ok.push(groupId);
      } catch (e: any) {
        const name = groups.find((g) => g.id === groupId)?.name ?? '모임';
        if (e?.response?.status === 409) ok.push(groupId); // 이미 올라가 있음 — 결과적으로 공유된 상태
        else failed.push(`${name}: ${e?.response?.data?.message ?? '네트워크 오류'}`);
      }
    }
    setSending(false);
    setShared((prev) => new Set([...prev, ...ok]));
    setSelected(new Set());
    if (failed.length) {
      Alert.alert('일부 공유 실패', failed.join('\n'));
    } else {
      Alert.alert('공유 완료', '모임 피드에 운동 기록을 올렸어요 💪');
      onShared?.(ok);
      onClose();
    }
  };

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.backdrop}>
        <View style={styles.sheet}>
          <View style={styles.header}>
            <Text style={styles.title}>모임 피드에 공유하기</Text>
            <TouchableOpacity onPress={onClose} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
              <X size={22} color={COLORS.textSecondary} strokeWidth={2} />
            </TouchableOpacity>
          </View>

          <ScrollView style={{ maxHeight: 520 }} contentContainerStyle={{ gap: SPACING.lg }} keyboardShouldPersistTaps="handled">
            <View style={{ gap: SPACING.sm }}>
              <Text style={styles.label}>공유할 모임</Text>
              {loading ? (
                <ActivityIndicator color={COLORS.primary} />
              ) : groups.length === 0 ? (
                <Text style={styles.empty}>아직 참여한 모임이 없어요. 모임 탭에서 만들거나 참여해보세요.</Text>
              ) : (
                groups.map((g) => {
                  const isShared = shared.has(g.id);
                  const isOn = selected.has(g.id);
                  return (
                    <TouchableOpacity
                      key={g.id}
                      style={[styles.groupRow, isOn && styles.groupRowOn, isShared && styles.groupRowDone]}
                      onPress={() => toggle(g.id)}
                      disabled={isShared}
                      activeOpacity={0.8}
                    >
                      <Users size={18} color={isShared ? COLORS.textMuted : COLORS.primary} strokeWidth={2} />
                      <Text style={[styles.groupName, isShared && styles.muted]} numberOfLines={1}>{g.name}</Text>
                      {isShared ? (
                        <Text style={styles.doneTag}>공유됨</Text>
                      ) : (
                        <View style={[styles.check, isOn && styles.checkOn]}>
                          {isOn && <Check size={14} color={COLORS.black} strokeWidth={3} />}
                        </View>
                      )}
                    </TouchableOpacity>
                  );
                })
              )}
            </View>

            <View style={{ gap: SPACING.sm }}>
              <Text style={styles.label}>사진 (선택)</Text>
              {photo ? (
                <View>
                  <Image source={{ uri: photo.uri }} style={styles.preview} resizeMode="cover" />
                  <TouchableOpacity style={styles.removePhoto} onPress={() => setPhoto(null)}>
                    <X size={16} color={COLORS.white} strokeWidth={2.5} />
                  </TouchableOpacity>
                </View>
              ) : (
                <TouchableOpacity style={styles.photoBtn} onPress={pickPhoto} activeOpacity={0.8}>
                  <ImagePlus size={22} color={COLORS.primary} strokeWidth={2} />
                  <Text style={styles.photoText}>사진 추가</Text>
                </TouchableOpacity>
              )}
            </View>

            <View style={{ gap: SPACING.sm }}>
              <Text style={styles.label}>한마디</Text>
              <TextInput
                style={styles.input}
                value={caption}
                onChangeText={(t) => setCaption(t.slice(0, CAPTION_MAX))}
                placeholder="오늘 운동 어땠나요?"
                placeholderTextColor={COLORS.textPlaceholder}
                selectionColor={COLORS.primary}
                multiline
                maxLength={CAPTION_MAX}
              />
              <Text style={styles.notice}>모임에는 운동 종목 · 횟수 · 운동 시간만 보여요. 싱크로율은 공개되지 않아요.</Text>
            </View>
          </ScrollView>

          <Button
            title={selected.size > 1 ? `${selected.size}개 모임에 공유하기` : '공유하기'}
            loading={sending}
            disabled={selected.size === 0}
            onPress={submit}
            style={{ marginTop: SPACING.lg }}
          />
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: COLORS.overlay, justifyContent: 'center', paddingHorizontal: SPACING.xl },
  sheet: { backgroundColor: COLORS.surface, borderRadius: RADIUS.xl, borderWidth: 1, borderColor: COLORS.cardBorder, padding: SPACING.xl },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: SPACING.lg },
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  label: { fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.text },
  empty: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted },
  groupRow: {
    flexDirection: 'row', alignItems: 'center', gap: SPACING.md,
    backgroundColor: COLORS.card, borderRadius: RADIUS.md, borderWidth: 1, borderColor: COLORS.cardBorder,
    paddingHorizontal: SPACING.lg, paddingVertical: SPACING.md,
  },
  groupRowOn: { borderColor: COLORS.primary },
  groupRowDone: { opacity: 0.6 },
  groupName: { flex: 1, fontSize: FONT_SIZE.md, fontWeight: '600', color: COLORS.text },
  muted: { color: COLORS.textMuted },
  doneTag: { fontSize: FONT_SIZE.xs, fontWeight: '700', color: COLORS.textMuted },
  check: { width: 22, height: 22, borderRadius: 6, borderWidth: 1.5, borderColor: COLORS.textMuted, alignItems: 'center', justifyContent: 'center' },
  checkOn: { backgroundColor: COLORS.primary, borderColor: COLORS.primary },
  photoBtn: {
    height: 96, borderRadius: RADIUS.md, borderWidth: 1, borderStyle: 'dashed', borderColor: COLORS.primary,
    backgroundColor: COLORS.primaryDim, alignItems: 'center', justifyContent: 'center', gap: 6,
  },
  photoText: { fontSize: FONT_SIZE.sm, fontWeight: '600', color: COLORS.primary },
  preview: { width: '100%', aspectRatio: 4 / 3, borderRadius: RADIUS.md, backgroundColor: COLORS.card },
  removePhoto: {
    position: 'absolute', top: SPACING.sm, right: SPACING.sm, width: 30, height: 30, borderRadius: 15,
    backgroundColor: 'rgba(0,0,0,0.6)', alignItems: 'center', justifyContent: 'center',
  },
  input: {
    minHeight: 64, maxHeight: 120, backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder,
    borderRadius: RADIUS.md, paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm, color: COLORS.text,
    fontSize: FONT_SIZE.sm, textAlignVertical: 'top',
  },
  notice: { fontSize: FONT_SIZE.xs, color: COLORS.textMuted, lineHeight: 18 },
});
