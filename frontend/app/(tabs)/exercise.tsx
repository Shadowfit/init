import { useState, useEffect, useRef, useCallback } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Alert, AppState, Platform, type AppStateStatus } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useLocalSearchParams, useFocusEffect } from 'expo-router';
import { exercisesService } from '@/services/exercisesService';
import { aiService, aiConfigError } from '@/services/aiService';
import type { FeedbackTemplate } from '@/types/feedback';
import { FEEDBACK_TYPE_LABEL } from '@/types/feedback';
import type { AiFeedbackType, LungeProgress } from '@/types/pose';
import {
  Camera as CameraIcon,
  ChevronLeft,
  Grid3x3,
  Maximize,
  Zap,
  ChevronUp,
  ChevronDown,
  Play,
} from 'lucide-react-native';
import { useRouter } from 'expo-router';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';

/** 백엔드 exerciseId → AI exercise_type 매핑 */
function exerciseTypeOf(exerciseId: number): string {
  // 백엔드 시드: 1=스쿼트, 2=런지, 3=플랭크
  // 선택 가능한 종목은 스쿼트와 전방 런지다.
  switch (exerciseId) {
    case 1: return 'squat';
    case 2: return 'lunge';
    case 3: return 'plank';
    default: return 'squat';
  }
}

export default function ExerciseScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ exerciseId?: string }>();
  // 운동 종목 — 라우트 param 우선, 기본 1(스쿼트). 추후 종목 선택 UI 추가 시 정리.
  const [exerciseId, setExerciseId] = useState(params.exerciseId === '2' ? 2 : 1);
  const isLunge = exerciseId === 2;
  // 스쿼트 깊이 모드(FULL/HALF, 2026-09-21). 종목 선택과 같은 화면에서 운동 시작 전에 고른다 —
  // 녹화 중엔 종목 칩과 동일하게 잠근다(선택은 한 번, 세션 시작 시점에 고정).
  const [squatDepthMode, setSquatDepthMode] = useState<'FULL' | 'HALF'>('FULL');
  const [lunge, setLunge] = useState<LungeProgress | null>(null);
  const [captureMessage, setCaptureMessage] = useState<string | null>(null);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [cameraRetryKey, setCameraRetryKey] = useState(0);

  const [permission, requestPermission] = useCameraPermissions();
  const [isRecording, setIsRecording] = useState(false);
  const [syncRate, setSyncRate] = useState(0);
  const [guideOpen, setGuideOpen] = useState(false);

  // 백엔드 세션 (POST /exercises/sessions → PATCH /sessions/{id}/end)
  const [sessionId, setSessionId] = useState<number | null>(null);
  // 세션 소유권 비밀값 (#187). sessionId 와 **같이 살고 같이 죽는다** — 화면 상태로만 들고
  // 저장소에 남기지 않는다. 앱 프로세스가 완전히 죽으면(cold start) 이 값도 같이 잃는데,
  // 그건 GET /sessions/active + POST reattach 로 되찾는다(아래 tryResume) — 로컬에 영속시킬
  // 필요가 없다(§4, session-resume-and-ai-state.md — sessionNonce 는 재부착 응답에도 실려온다).
  const [sessionNonce, setSessionNonce] = useState<string | null>(null);
  // AI 워커 인덱스 (2026-08-26). sessionNonce 와 같은 이유로 화면 상태로만 들고 다닌다 —
  // AI 프레임(POST /pose)마다 X-AI-Worker 헤더로 실어야 nginx가 세션 시작 때와 같은
  // 워커로 고정 전달한다. 안 실으면 커널이 무작위 분산시켜 세션 상태가 없는 워커로 갈 수 있다.
  const [aiWorkerIndex, setAiWorkerIndex] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const lastRouteExercise = useRef(params.exerciseId);
  useEffect(() => {
    if (lastRouteExercise.current === params.exerciseId) return;
    lastRouteExercise.current = params.exerciseId;
    if (!isRecording && !busy && sessionId == null) {
      setExerciseId(params.exerciseId === '2' ? 2 : 1);
      setLunge(null);
    }
  }, [params.exerciseId, isRecording, busy, sessionId]);
  // 페르소나 멘트 프리로드 (TTS 보류 — 일단 받아서 보관만)
  const [, setFeedbackTemplates] = useState<FeedbackTemplate[]>([]);
  // AI 응답 — 최근 자세 결함 라벨 (시각 표시용)
  const [lastFeedback, setLastFeedback] = useState<AiFeedbackType | null>(null);
  const [coachingNotice, setCoachingNotice] = useState<{ message: string; success: boolean } | null>(null);
  // AI 응답 — 누적 횟수
  const [repCount, setRepCount] = useState(0);
  // 카메라 ref (takePictureAsync 호출용)
  const cameraRef = useRef<CameraView | null>(null);

  // 앱 포그라운드 여부 — 백그라운드에서는 폴링만 멈춘다. 세션은 끝내지 않는다(재개로 이어간다).
  const [isForeground, setIsForeground] = useState(true);
  // 이 화면이 포커스돼 있는지 — 다른 탭으로 이동해도 화면은 마운트된 채 남으므로
  // (`_layout.tsx` 에 unmountOnBlur 없음) 별도로 추적해야 폴링을 멈출 수 있다.
  const [isScreenFocused, setIsScreenFocused] = useState(true);
  // tryResume 동시 호출 가드 — 포커스·포그라운드 전환이 거의 같이 일어나 중복 호출될 수 있다.
  const resumingRef = useRef(false);

  // ── 이어하기(#59) ────────────────────────────────────────────────────────
  // 진행 중이던 세션을 찾아 재부착한다. sessionId 를 이미 알고 있으면(같은 마운트 동안
  // 배경↔전경만 오간 경우) AI 상태는 살아있으므로 다시 물을 필요가 없다 — cold start 로
  // 이 화면 인스턴스가 세션을 모를 때만 서버에 묻는다.
  const tryResume = useCallback(async () => {
    if (sessionId != null || isRecording || busy || resumingRef.current) return;
    resumingRef.current = true;
    try {
      const active = await exercisesService.getActiveSession();
      if (active.status === 204 || active.data?.sessionId == null) return; // 이어할 세션 없음
      if (active.data.endTime != null) return; // 이미 종료 요청됨 — 이어하기 권하면 안 됨

      try {
        const re = await exercisesService.reattachSession(active.data.sessionId);
        const r = re.data;
        setExerciseId(active.data.exerciseId);
        setSessionId(r.sessionId);
        setSessionNonce(r.sessionNonce);
        setAiWorkerIndex(r.aiWorkerIndex ?? null);
        setRepCount(r.restoredRepCount);
        setIsRecording(true);
        if (r.analyzerStateReset) {
          // 감수하기로 확정된 손실(session-resume-and-ai-state.md §4-0) — 그대로 노출해도
          // 되게 쓰인 문구다.
          Alert.alert('운동을 이어합니다', r.message);
        }
      } catch (e: any) {
        const status = e?.response?.status;
        if (status === 410) {
          Alert.alert('이어하기 시간 초과', '너무 오래 지나 이어할 수 없습니다. 새로 시작해주세요.');
        } else if (status === 503) {
          Alert.alert(
            'AI 서버 연결 실패',
            '잠시 후 다시 시도해주세요. 진행 중이던 운동 기록은 그대로 남아 있습니다.',
          );
        }
        // 404(남의 세션/없음/이미 끝남) 는 조용히 포기 — 평소대로 "시작" 버튼을 그대로 둔다.
      }
    } catch {
      // GET /sessions/active 실패 — 조용히 포기. 사용자는 그냥 새로 시작할 수 있다.
    } finally {
      resumingRef.current = false;
    }
  }, [sessionId, isRecording, busy]);

  // 화면 포커스 — 최초 진입 + 다른 탭에서 돌아올 때. 포커스를 잃으면 폴링을 멈춘다(§2).
  useFocusEffect(
    useCallback(() => {
      setIsScreenFocused(true);
      tryResume();
      return () => setIsScreenFocused(false);
    }, [tryResume]),
  );

  // 앱 포그라운드/백그라운드 — 백그라운드 진입 시 세션은 끝내지 않고 폴링만 멈춘다.
  // 복귀 시 이 화면 인스턴스가 세션을 잃은 상태(cold start)였다면 tryResume 이 되찾는다.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (next: AppStateStatus) => {
      setIsForeground(next === 'active');
    });
    return () => sub.remove();
  }, []);
  useEffect(() => {
    if (isForeground) tryResume();
  }, [isForeground, tryResume]);

  const handleToggleRecord = async () => {
    if (busy) return;

    // AI 주소가 없거나 https 가 아니면 **시작 전에** 말한다 (이슈 #148).
    //
    // 예전에는 릴리스 빌드가 조용히 http://localhost:8000 을 만들었고, ATS·cleartext 정책이
    // 그걸 막아 «세션은 시작됐는데 카운트가 안 오르는» 상태가 됐다. 원인이 주소라는 단서가
    // 어디에도 없었다. 세션을 만들기 전에 걸러야 DB 에 못 쓸 세션이 안 남는다.
    if (!isRecording && aiConfigError) {
      Alert.alert('AI 서버 설정 오류', aiConfigError);
      return;
    }

    if (!isRecording && !process.env.EXPO_PUBLIC_AI_PUBLIC_TOKEN) {
      Alert.alert('AI 서버 설정 오류', 'AI 연결 설정이 없어 운동을 분석할 수 없습니다.');
      return;
    }

    setBusy(true);
    try {
      if (!isRecording) {
        setLunge(null);
        setCaptureMessage(null);
        // ── 시작: 백엔드에 세션 생성 + 멘트 프리로드 ─────────
        const res = await exercisesService.startSession({
          exerciseId,
          depthMode: isLunge ? undefined : squatDepthMode,
        });
        setSessionId(res.data.sessionId);
        setSessionNonce(res.data.sessionNonce);
        setAiWorkerIndex(res.data.aiWorkerIndex ?? null);
        // 멘트 프리로드는 실패해도 운동은 진행
        exercisesService
          .getFeedbackTemplates(exerciseId)
          .then((tpl) => setFeedbackTemplates(tpl.data))
          .catch(() => {});
        setIsRecording(true);
      } else {
        // ── 종료: 백엔드 종료 통보 + 리포트로 이동 ──────────
        const id = sessionId;
        if (id != null) await exercisesService.endSession(id);
        setIsRecording(false);
        // 비밀값은 세션과 함께 버린다 (#187). 리포트 화면으로 넘어가면 어차피 언마운트되지만,
        // «끝난 세션의 자격증명이 메모리에 남아 있다» 를 화면 전환 타이밍에 기대지 않는다.
        setSessionNonce(null);
        setAiWorkerIndex(null);
        setSessionId(null);
        if (id != null) {
          if (isLunge) {
            const sides = lunge
              ? `왼쪽 ${lunge.left_reps}회 · 오른쪽 ${lunge.right_reps}회` +
                (lunge.unassigned_reps ? `\n이전 기록 ${lunge.unassigned_reps}회 (좌우 정보 없음)` : '')
              : '좌우 정보가 아직 없습니다.';
            Alert.alert('전방 런지 종료', `총 ${repCount}회\n${sides}\n자세 점수는 측정하지 않습니다.`);
          } else {
            router.replace(`/report/${id}` as any);
          }
        }
      }
    } catch (e: any) {
      console.error('[exercise session] status=', e?.response?.status, 'data=', e?.response?.data);
      Alert.alert(
        '운동 세션 오류',
        e?.response?.data?.message ?? '세션 처리 중 오류가 발생했습니다',
      );
      // 시작 실패면 녹화 상태 유지 안 함 / 종료 실패면 일단 화면은 종료 처리됨
      if (!isRecording) setIsRecording(false);
    } finally {
      setBusy(false);
    }
  };

  // 녹화 정지 시 참고 유사도, 알림, 횟수 초기화
  useEffect(() => {
    if (!isRecording) {
      setSyncRate(0);
      setLastFeedback(null);
      setCoachingNotice(null);
      setRepCount(0);
      setLunge(null);
      setCaptureMessage(null);
    }
  }, [isRecording]);

  // 자세 결함 토스트 — 4초 후 자동 사라짐
  useEffect(() => {
    if (!lastFeedback) return;
    const t = setTimeout(() => setLastFeedback(null), 4000);
    return () => clearTimeout(t);
  }, [lastFeedback]);

  useEffect(() => {
    if (!coachingNotice) return;
    const t = setTimeout(() => setCoachingNotice(null), 4000);
    return () => clearTimeout(t);
  }, [coachingNotice]);

  // ── AI 폴링 (분기 H2) ────────────────────────────────────
  // takePictureAsync 는 셔터·인코딩 비용이 크므로 10fps 는 비현실적.
  // ~3fps (330ms) 로 시작, 실제 디바이스에서 부담되면 더 줄여도 됨.
  // AI_PUBLIC_TOKEN 미설정 시 폴링 비활성 (DEV 시연 모드 — DEV 패널만 동작)
  // 내부 gRPC 토큰과 값이 분리돼 있다 (이슈 #134) — aiService.ts 주석 참고
  useEffect(() => {
    // nonce 가 없으면 폴링을 시작하지 않는다 (#187 2단계). 보내봐야 AI 가 전부 버리므로,
    // 프레임을 쏘면서 «세션 없음» 을 반복해 받느니 아예 시작을 안 하는 편이 낫다.
    // 화면이 포커스를 잃었거나 앱이 백그라운드면 폴링 자체를 안 돈다(§2) — 세션은 끝내지
    // 않고 그대로 두며, 돌아오면 이 effect 가 재실행돼 이어서 폴링한다.
    if (busy || !isRecording || sessionId == null || sessionNonce == null || !isForeground || !isScreenFocused) return;
    const token = process.env.EXPO_PUBLIC_AI_PUBLIC_TOKEN;
    if (!token) return;

    let cancelled = false;
    // AI가 느려질수록 이전 tick이 안 끝난 채 다음 tick이 겹쳐 쌓이는 걸 막는 가드
    // (백프레셔 부재, 이슈 #554) — 없으면 서버가 느려질수록 in-flight 요청 수가 늘어나
    // 오히려 부하를 더 얹는 방향으로 간다.
    let inFlight = false;
    const exerciseType = exerciseTypeOf(exerciseId);
    const intervalMs = 330; // ~3fps — takePictureAsync 부담 고려

    const tick = async () => {
      if (cancelled || inFlight) return;
      inFlight = true;
      try {
        let image: string | undefined;
        try {
          const cam = cameraRef.current;
          if (cam) {
            const snap = await cam.takePictureAsync({
              base64: true,
              quality: 0.4, // 분석엔 충분, 페이로드 줄임
              skipProcessing: false, // 분석 좌표계가 기기 회전과 일치하도록 처리
              shutterSound: false,
            });
            if (snap?.base64) image = snap.base64;
          }
        } catch (e) {
          if (__DEV__) {
            // eslint-disable-next-line no-console
            console.warn('[camera capture]', (e as Error).message);
          }
        }

        if (cancelled) return;
        if (!image) {
          setCaptureMessage('카메라 영상을 가져오지 못했습니다. 카메라 상태를 확인해주세요.');
          return;
        }
        try {
          // timestamp_sec 은 더 보내지 않는다 (이슈 #156). 여기서 보내던 Date.now()/1000 은
          // epoch 라 «세션 시작 기준 경과 초» 가 아니었고, 변환하는 곳이 한 군데도 없어 그대로
          // 리포트까지 흘러 시각 표시가 무의미해졌다. 이제 서버가 도착 시각으로 만든다.
          const res = await aiService.detectPose(
            {
              image,
              exercise_type: exerciseType,
              session_id: sessionId,
              session_nonce: sessionNonce,
            },
            aiWorkerIndex,
          );
          if (cancelled) return;
          const r = res.data;
          if (r.lunge) setLunge(r.lunge);
          if (r.success === false) {
            if (r.skip_reason !== 'RATE_LIMITED') {
              setCaptureMessage(r.lunge?.guidance ?? r.message ?? '전신이 보이도록 위치를 조정해주세요.');
            }
            // success 는 «판정에 들어갔는가» 다(#267) — RATE_LIMITED·NO_POSE·LOW_VISIBILITY 는
            // 정상 동작 중에도 나는 스킵이라 폴링을 유지한다. 세션·순서 문제만 치명적이다.
            const fatal =
              r.skip_reason === 'NO_LEASE' ||
              r.skip_reason === 'SESSION_NOT_FOUND' ||
              r.skip_reason === 'UNSUPPORTED_EXERCISE';
            if (fatal) {
              setIsRecording(false);
              Alert.alert(
                '운동 세션 연결 끊김',
                r.message ?? '세션이 종료됐거나 서버가 재시작됐습니다. 다시 시작해주세요.',
              );
            }
            return;
          }
          setCaptureMessage(null);
          if (r.sync_rate != null) setSyncRate(Math.round(r.sync_rate));
          if (r.message) {
            setCoachingNotice({ message: r.message, success: r.rep_completed === true });
            setLastFeedback(null);
          } else if (r.feedback_type) {
            setLastFeedback(r.feedback_type);
            setCoachingNotice(null);
          }
          if (r.rep_count != null) setRepCount(r.rep_count);
        } catch (e: any) {
          if (!cancelled) setCaptureMessage('AI 연결을 확인하고 있습니다. 잠시 원위치에서 기다려주세요.');
          if (__DEV__) {
            // eslint-disable-next-line no-console
            console.warn('[ai pose] status=', e?.response?.status, e?.message);
          }
        }
      } finally {
        inFlight = false;
      }
    };

    const id = setInterval(tick, intervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [busy, isRecording, sessionId, sessionNonce, exerciseId, aiWorkerIndex, isForeground, isScreenFocused]);

  // 카메라 권한 없을 때
  if (!permission) {
    return (
      <View style={styles.center}>
        <Text style={styles.permText}>카메라 연결 중...</Text>
      </View>
    );
  }

  if (!permission.granted) {
    return (
      <View style={styles.center}>
        <CameraIcon size={48} color={COLORS.textMuted} strokeWidth={1.75} />
        <Text style={styles.permTitle}>카메라 권한이 필요합니다</Text>
        <Text style={styles.permDesc}>
          실시간 자세 분석을 위해 카메라 접근을 허용해주세요.
        </Text>
        <Button
          title="카메라 허용"
          onPress={requestPermission}
          style={{ marginTop: SPACING.xl }}
        />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* 카메라 뷰 */}
      <CameraView
        key={cameraRetryKey}
        ref={cameraRef}
        style={styles.camera}
        facing="front"
        onMountError={(error) => setCameraError(error.message || '카메라 연결에 실패했습니다.')}
      >
        {/* 상단 UI 오버레이 */}
        <View style={styles.topOverlay}>
          <TouchableOpacity onPress={() => router.back()} style={styles.iconBtn}>
            <ChevronLeft size={20} color={COLORS.text} strokeWidth={2} />
          </TouchableOpacity>
          <View style={styles.topRight}>
            <TouchableOpacity style={styles.iconBtn}>
              <Grid3x3 size={18} color={COLORS.text} strokeWidth={2} />
            </TouchableOpacity>
            <TouchableOpacity style={styles.iconBtn}>
              <Maximize size={18} color={COLORS.text} strokeWidth={2} />
            </TouchableOpacity>
          </View>
        </View>

        {cameraError && (
          <View style={styles.cameraErrorPanel}>
            <CameraIcon size={32} color={COLORS.textMuted} strokeWidth={1.75} />
            <Text style={styles.permTitle}>카메라를 열 수 없습니다</Text>
            <Text style={styles.permDesc}>
              {Platform.OS === 'web'
                ? '브라우저의 카메라 권한과 연결된 카메라를 확인해주세요.'
                : '기기의 카메라 권한과 연결 상태를 확인해주세요.'}
            </Text>
            {__DEV__ && <Text style={styles.permDesc}>{cameraError}</Text>}
            <Button
              title="다시 시도"
              onPress={() => { setCameraError(null); setCameraRetryKey((key) => key + 1); }}
              style={{ marginTop: SPACING.lg }}
            />
          </View>
        )}

        {/* 종목 선택과 측정 상태 */}
        <View style={styles.exercisePicker}>
          {([{ id: 1, name: '스쿼트' }, { id: 2, name: '전방 런지' }]).map((exercise) => (
            <TouchableOpacity
              key={exercise.id}
              accessibilityRole="button"
              accessibilityState={{ selected: exerciseId === exercise.id, disabled: isRecording || busy }}
              disabled={isRecording || busy}
              onPress={() => { setExerciseId(exercise.id); setLunge(null); setCaptureMessage(null); }}
              style={[styles.exerciseChoice, exerciseId === exercise.id && styles.exerciseSelected]}
            >
              <Text style={styles.exerciseChoiceText}>{exercise.name}</Text>
            </TouchableOpacity>
          ))}
        </View>
        {!isLunge && (
          <View style={styles.depthModePicker}>
            {([{ id: 'FULL', label: '풀 스쿼트' }, { id: 'HALF', label: '하프 스쿼트' }] as const).map((mode) => (
              <TouchableOpacity
                key={mode.id}
                accessibilityRole="button"
                accessibilityState={{ selected: squatDepthMode === mode.id, disabled: isRecording || busy }}
                disabled={isRecording || busy}
                onPress={() => setSquatDepthMode(mode.id)}
                style={[styles.depthModeChoice, squatDepthMode === mode.id && styles.exerciseSelected]}
              >
                <Text style={styles.exerciseChoiceText}>{mode.label}</Text>
              </TouchableOpacity>
            ))}
          </View>
        )}
        {isLunge ? (
          <View style={styles.lungePanel}>
            <Text style={styles.lungeTitle}>전방 런지 · 횟수 측정</Text>
            <Text style={styles.guideRow}>{captureMessage ?? lunge?.guidance ?? '옆모습과 두 발이 보이게 서주세요. 앞발을 내딛고 굽힌 뒤 원위치로 돌아오면 1회입니다.'}</Text>
            {isRecording && <Text style={styles.lungeSides}>{lunge ? `왼쪽 ${lunge.left_reps}회 · 오른쪽 ${lunge.right_reps}회` : '좌우 횟수 확인 중...'}</Text>}
            {!!lunge?.unassigned_reps && <Text style={styles.guideRow}>이전 {lunge.unassigned_reps}회는 좌우 정보 없이 복원되었습니다.</Text>}
            <Text style={styles.guideRow}>자세 점수는 측정하지 않습니다.</Text>
          </View>
        ) : (
        <View style={styles.syncGauge}>
          <Text style={styles.syncLabel}>동작 유사도 · 참고용</Text>
          <Text style={styles.syncValue}>{isRecording && syncRate > 0 ? `${syncRate}%` : '측정 전'}</Text>
        </View>
        )}

        {/* 자세 결함 토스트 (AI feedback_type 응답) */}
        {(coachingNotice || lastFeedback) && (
          <View style={[styles.feedbackToast, coachingNotice?.success && styles.feedbackToastSuccess]}>
            <Zap size={14} color={coachingNotice?.success ? COLORS.success : COLORS.warning}
              strokeWidth={2} fill={coachingNotice?.success ? COLORS.success : COLORS.warning} />
            <Text style={styles.feedbackToastText}>
              {coachingNotice?.message ?? (lastFeedback ? FEEDBACK_TYPE_LABEL[lastFeedback] ?? lastFeedback : '')}
            </Text>
          </View>
        )}

        {/* AI가 완료 동작으로 판정한 횟수 */}
        <View style={styles.repCounter}>
          <Text style={styles.repValue}>{repCount}</Text>
          <Text style={styles.repLabel}>완료한 횟수</Text>
        </View>

        {/* 촬영 가이드 (접기/펴기) */}
        <TouchableOpacity
          style={styles.guideToggle}
          onPress={() => setGuideOpen(!guideOpen)}
          activeOpacity={0.7}
        >
          <Text style={styles.guideToggleText}>촬영 가이드</Text>
          {guideOpen ? (
            <ChevronUp size={14} color={COLORS.textSecondary} strokeWidth={2} />
          ) : (
            <ChevronDown size={14} color={COLORS.textSecondary} strokeWidth={2} />
          )}
        </TouchableOpacity>
        {guideOpen && (
          <View style={styles.guidePanel}>
            <Text style={styles.guideRow}>{isLunge ? '📐  측면에 카메라 고정 · 양쪽 무릎과 발끝이 보여야 합니다' : '📐  정면 기준 45° 위치에 카메라 고정'}</Text>
            {isLunge && <Text style={styles.guideRow}>🦶  뒷발 위치를 유지하고 앞발을 처음 자리로 되돌려주세요</Text>}
            <Text style={styles.guideRow}>🧍  머리부터 발목까지 여백을 두고 촬영</Text>
            <Text style={styles.guideRow}>⏱️  시작과 종료 때 똑바로 선 자세를 2초 유지</Text>
            <Text style={styles.guideRow}>💡  밝은 조명, 단색 배경 권장</Text>
            <Text style={styles.guideRow}>🚫  무릎을 가리는 랙·기구와 여러 사람 환경은 피해주세요</Text>
          </View>
        )}

        {/* 하단: 기준영상 + 녹화 버튼 */}
        <View style={styles.bottomOverlay}>
          <View style={styles.bottomCenter}>
            <Text style={styles.recordHint}>
              {isRecording ? '운동 중...' : '버튼을 눌러 녹화를 시작하세요'}
            </Text>
            <TouchableOpacity
              style={[
                styles.recordBtn,
                isRecording && styles.recordBtnActive,
                busy && { opacity: 0.6 },
              ]}
              onPress={handleToggleRecord}
              disabled={busy}
              activeOpacity={0.7}
            >
              <View
                style={[styles.recordDot, isRecording && styles.recordDotActive]}
              />
            </TouchableOpacity>
          </View>

          {/* 기준 영상 미리보기 */}
          {!isLunge && <TouchableOpacity style={styles.refVideoBtn} activeOpacity={0.7}>
            <Play size={14} color={COLORS.primary} strokeWidth={2} fill={COLORS.primary} />
            <Text style={styles.refVideoText}>기준 영상</Text>
          </TouchableOpacity>}
        </View>
      </CameraView>
    </View>
  );
}

const styles = StyleSheet.create({
  exercisePicker: { flexDirection: 'row', gap: 8, marginHorizontal: 20, marginTop: 12 },
  exerciseChoice: { paddingHorizontal: 18, paddingVertical: 10, borderRadius: 20, backgroundColor: 'rgba(0,0,0,0.6)', borderWidth: 1, borderColor: 'transparent' },
  exerciseSelected: { borderColor: COLORS.primary },
  exerciseChoiceText: { color: COLORS.text, fontWeight: '700' },
  depthModePicker: { flexDirection: 'row', gap: 8, marginHorizontal: 20, marginTop: 8 },
  depthModeChoice: { paddingHorizontal: 16, paddingVertical: 8, borderRadius: 18, backgroundColor: 'rgba(0,0,0,0.5)', borderWidth: 1, borderColor: 'transparent' },
  lungePanel: { margin: 20, padding: 16, borderRadius: 16, backgroundColor: 'rgba(0,0,0,0.75)', gap: 8 },
  lungeTitle: { color: COLORS.primary, fontSize: 17, fontWeight: '700' },
  lungeSides: { color: COLORS.text, fontSize: 20, fontWeight: '700' },
  container: { flex: 1, backgroundColor: COLORS.black },
  center: {
    flex: 1,
    backgroundColor: COLORS.background,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
  },
  permText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.md },
  permTitle: {
    color: COLORS.text,
    fontSize: FONT_SIZE.lg,
    fontWeight: '700',
    marginTop: SPACING.xl,
  },
  permDesc: {
    color: COLORS.textSecondary,
    fontSize: FONT_SIZE.sm,
    textAlign: 'center',
    marginTop: SPACING.sm,
  },

  camera: { flex: 1 },
  cameraErrorPanel: {
    position: 'absolute',
    top: '32%',
    left: SPACING.lg,
    right: SPACING.lg,
    zIndex: 20,
    padding: SPACING.xl,
    borderRadius: RADIUS.md,
    backgroundColor: COLORS.surface,
    alignItems: 'center',
  },

  // Top overlay
  topOverlay: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingTop: 50,
    paddingHorizontal: SPACING.lg,
  },
  topRight: { flexDirection: 'row', gap: SPACING.md },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.4)',
    justifyContent: 'center',
    alignItems: 'center',
  },

  // 동작 유사도 참고 정보
  syncGauge: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: 'rgba(0,0,0,0.6)',
    marginHorizontal: SPACING.lg,
    marginTop: SPACING.lg,
    borderRadius: RADIUS.sm,
    paddingHorizontal: SPACING.md,
    paddingVertical: SPACING.sm,
    gap: SPACING.sm,
  },
  syncLabel: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs },
  syncValue: {
    fontSize: FONT_SIZE.md,
    fontWeight: '800',
    color: COLORS.text,
    minWidth: 40,
    textAlign: 'right',
  },

  // 자세 결함 토스트
  feedbackToast: {
    position: 'absolute',
    top: 130,
    left: 0,
    right: 0,
    marginHorizontal: 32,
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
    backgroundColor: 'rgba(0,0,0,0.72)',
    borderRadius: RADIUS.full,
    borderWidth: 1,
    borderColor: COLORS.warning,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.sm,
  },
  feedbackToastSuccess: {
    borderColor: COLORS.success,
  },
  feedbackToastText: {
    color: COLORS.text,
    fontSize: FONT_SIZE.md,
    fontWeight: '700',
  },

  // 운동 횟수 카운터
  repCounter: {
    position: 'absolute',
    top: '40%',
    right: SPACING.xxl,
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
    borderRadius: RADIUS.lg,
    minWidth: 72,
  },
  repValue: {
    fontSize: 40,
    fontWeight: '900',
    color: COLORS.primary,
    lineHeight: 44,
  },
  repLabel: {
    fontSize: FONT_SIZE.xs,
    color: COLORS.textSecondary,
    fontWeight: '600',
  },

  // Bottom overlay
  bottomOverlay: {
    position: 'absolute',
    bottom: 30,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'center',
    paddingHorizontal: SPACING.xxl,
  },
  bottomCenter: { alignItems: 'center', flex: 1 },
  recordHint: {
    color: COLORS.textSecondary,
    fontSize: FONT_SIZE.sm,
    marginBottom: SPACING.md,
  },
  recordBtn: {
    width: 72,
    height: 72,
    borderRadius: 36,
    borderWidth: 4,
    borderColor: COLORS.white,
    justifyContent: 'center',
    alignItems: 'center',
  },
  recordBtnActive: { borderColor: COLORS.error },
  recordDot: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: COLORS.error,
  },
  recordDotActive: {
    width: 28,
    height: 28,
    borderRadius: 6,
  },

  refVideoBtn: {
    position: 'absolute',
    right: SPACING.xxl,
    bottom: 10,
    alignItems: 'center',
    gap: 4,
  },
  refVideoText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs },

  // 촬영 가이드
  guideToggle: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.sm,
    backgroundColor: 'rgba(0,0,0,0.6)',
    marginHorizontal: SPACING.lg,
    marginTop: SPACING.sm,
    borderRadius: RADIUS.sm,
    paddingVertical: SPACING.sm,
  },
  guideToggleText: {
    fontSize: FONT_SIZE.xs,
    color: COLORS.textSecondary,
  },
  guidePanel: {
    backgroundColor: 'rgba(0,0,0,0.6)',
    marginHorizontal: SPACING.lg,
    marginTop: 4,
    borderRadius: RADIUS.sm,
    padding: SPACING.md,
    gap: SPACING.xs,
  },
  guideRow: {
    fontSize: FONT_SIZE.xs,
    color: COLORS.textSecondary,
    lineHeight: 20,
  },

});
