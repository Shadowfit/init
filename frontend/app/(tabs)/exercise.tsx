import { useState, useEffect, useRef, useCallback } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Animated, AppState, Platform, type AppStateStatus } from 'react-native';
import { Alert } from '@/utils/alert';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useLocalSearchParams, useFocusEffect } from 'expo-router';
import { exercisesService } from '@/services/exercisesService';
import { aiService, aiConfigError } from '@/services/aiService';
import type { FeedbackTemplate } from '@/types/feedback';
import { FEEDBACK_TYPE_LABEL } from '@/types/feedback';
import type { AiFeedbackType, LungeProgress, PoseSkipReason, SquatProgress } from '@/types/pose';
import ReadinessBanner, { deriveReadiness } from '@/components/exercise/ReadinessBanner';
import MannequinDemo from '@/components/exercise/MannequinDemo';
import { motionRecorder } from '@/lib/motion/recorder';
import * as Haptics from 'expo-haptics';
import { DEFAULT_SYNC_THRESHOLDS, syncColor as syncColorOf, syncThresholdsFrom, type SyncThresholds } from '@/constants/sync';
import {
  Camera as CameraIcon,
  ChevronLeft,
  Grid3x3,
  Maximize,
  Zap,
  ChevronUp,
  ChevronDown,
  Box,
  X,
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
  const [lunge, setLunge] = useState<LungeProgress | null>(null);
  // 운동 시작 전/중 «지금 뭘 해야 하는지» 배너용 상태 (ReadinessBanner)
  const [squat, setSquat] = useState<SquatProgress | null>(null);
  const [lastSkip, setLastSkip] = useState<PoseSkipReason | null>(null);
  const [gotResponse, setGotResponse] = useState(false);
  const [aiError, setAiError] = useState(false);
  // 횟수가 올라간 순간 화면 중앙에 잠깐 띄우는 «✓ N회»
  const [repFlash, setRepFlash] = useState<number | null>(null);
  const [captureMessage, setCaptureMessage] = useState<string | null>(null);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [cameraRetryKey, setCameraRetryKey] = useState(0);
  // 분석용 촬영 해상도(안드로이드). 지정하지 않으면 폰이 12MP(4000x3000) 원본을 찍는다 —
  // 2026-10-06 실기기에서 프레임 간격이 1.3~2초(≈0.5fps)로 늘어져 횟수를 못 셌다. 같은 사진을
  // 960px 로 줄여도 MediaPipe 관절 가시성은 유지(오히려 12MP 에서 검출 실패하던 프레임도 검출됨)된다.
  const [pictureSize, setPictureSize] = useState<string | undefined>(undefined);

  const [permission, requestPermission] = useCameraPermissions();
  const [isRecording, setIsRecording] = useState(false);
  const [syncRate, setSyncRate] = useState(0);
  // 이 종목의 판정 기준(정석·위험) — 세션 시작·재부착 응답으로 받는다. 관리자 › 운동 종목에서 정한 값.
  const [thresholds, setThresholds] = useState<SyncThresholds>(DEFAULT_SYNC_THRESHOLDS);
  const [guideOpen, setGuideOpen] = useState(false);
  // 3D 목각인형 시범 패널. 카메라와 같이 돌면 폰이 버벅일 수 있어 운동 중에는 띄우지 않는다.
  const [demoOpen, setDemoOpen] = useState(false);

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

  // 카메라가 준비되면 분석에 충분한 가장 작은 4:3 촬영 크기를 고른다(긴 변 1024px 이상).
  // 비율을 맞추는 이유: pictureSize 를 주면 ratio 설정이 무시돼, 미리보기와 다른 비율을 고르면
  // 사진이 미리보기와 다른 영역으로 잘린다. getAvailablePictureSizesAsync 는 안드로이드 전용이라
  // iOS 는 건드리지 않는다(기존 동작 유지). 실패해도 운동은 기존처럼 원본 해상도로 진행한다.
  const handleCameraReady = async () => {
    if (Platform.OS !== 'android') return;
    try {
      const sizes = (await cameraRef.current?.getAvailablePictureSizesAsync()) ?? [];
      const candidates = sizes
        .map((label) => {
          const [w, h] = label.split('x').map(Number);
          return { label, w, h };
        })
        .filter((s) => s.w > 0 && s.h > 0 && Math.abs(s.w / s.h - 4 / 3) < 0.02 && Math.max(s.w, s.h) >= 1024)
        .sort((a, b) => a.w * a.h - b.w * b.h);
      if (__DEV__) {
        // eslint-disable-next-line no-console
        console.log('[camera] pictureSize 선택:', candidates[0]?.label ?? '없음(원본 유지)', '/ 후보:', sizes.join(','));
      }
      if (candidates[0]) setPictureSize(candidates[0].label);
    } catch (e) {
      if (__DEV__) {
        // eslint-disable-next-line no-console
        console.warn('[camera] pictureSize 조회 실패 — 원본 해상도 유지:', (e as Error).message);
      }
    }
  };

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
        setThresholds(syncThresholdsFrom(r));
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
        // 하프 스쿼트는 시연 범위에서 뺐다(2026-10-06) — depthMode 를 생략하면 백엔드가 FULL 로 취급.
        const res = await exercisesService.startSession({ exerciseId });
        setSessionId(res.data.sessionId);
        // 3D 동작 비교용 관절 좌표 기록 시작(스쿼트·런지만 기록한다 — recorder 가 종목을 보고 거른다)
        motionRecorder.start(res.data.sessionId, exerciseTypeOf(exerciseId));
        setSessionNonce(res.data.sessionNonce);
        setAiWorkerIndex(res.data.aiWorkerIndex ?? null);
        setThresholds(syncThresholdsFrom(res.data));
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
        // 스쿼트·런지 모두 리포트로 이동한다 — 런지도 깊이·체간·보폭 점수가 리포트에 나온다.
        if (id != null) router.replace(`/report/${id}` as any);
      }
    } catch (e: any) {
      console.error('[exercise session] status=', e?.response?.status, 'data=', e?.response?.data);
      Alert.alert(
        '운동 세션 오류',
        e?.response?.data?.message ?? '세션 처리 중 오류가 발생했습니다',
      );
      // 시작 실패면 운동 상태 유지 안 함 / 종료 실패면 일단 화면은 종료 처리됨
      if (!isRecording) setIsRecording(false);
    } finally {
      setBusy(false);
    }
  };

  // 운동 종료 시 참고 유사도, 알림, 횟수 초기화
  useEffect(() => {
    if (!isRecording) {
      setSyncRate(0);
      setLastFeedback(null);
      setCoachingNotice(null);
      setRepCount(0);
      setLunge(null);
      setCaptureMessage(null);
      setSquat(null);
      setLastSkip(null);
      setGotResponse(false);
      setAiError(false);
      setRepFlash(null);
    }
  }, [isRecording]);

  // 횟수가 올라가면 «✓ N회» 를 1.2초 띄운다 — 폰 화면만 봐도 방금 인식된 걸 알 수 있게.
  const previousRepCount = useRef(0);
  useEffect(() => {
    if (repCount > previousRepCount.current) {
      setRepFlash(repCount);
      const timer = setTimeout(() => setRepFlash(null), 1200);
      previousRepCount.current = repCount;
      return () => clearTimeout(timer);
    }
    previousRepCount.current = repCount;
  }, [repCount]);

  const readiness = deriveReadiness({ isRecording, isLunge, gotResponse, aiError, lastSkip, squat, lunge });
  const liveSyncColor = syncColorOf(syncRate, thresholds);

  // 싱크로율이 위험 기준(warn) 아래로 떨어지면 진동 — 화면을 보지 않아도 알 수 있게.
  // 싱크로율은 반복 1회가 끝날 때 갱신되므로(AI 가 rep 단위로 채점) 진동도 반복마다 한 번이다.
  // 0 은 «아직 측정 전» 이라 진동하지 않는다. 런지는 횟수만 세는 종목이라 싱크로율이 없다.
  const prevSyncRef = useRef(syncRate);
  useEffect(() => {
    const prev = prevSyncRef.current;
    prevSyncRef.current = syncRate;
    if (!isRecording || isLunge || syncRate <= 0 || syncRate === prev || Platform.OS === 'web') return;
    if (syncRate < thresholds.warn) {
      // 위험 구간에 처음 들어오면 경고 진동, 계속 머물면 약한 진동
      if (prev >= thresholds.warn || prev <= 0) Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning).catch(() => {});
      else Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
    }
  }, [syncRate, isRecording, isLunge, thresholds]);

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
    // 2026-10-06 실기기(안드로이드) 첫 시험에서 프레임 간격이 1.3~2초(≈0.6fps)였다 — 카운팅 상수가
    // 3fps 기준이라 이 속도면 횟수를 못 센다. 원인 후보는 takePictureAsync 의 후처리(skipProcessing=false).
    // 끄면 EXIF 방향만 달린 사진이 올라오는데, 서버(cv2.imdecode)가 EXIF 방향을 반영하고 EXIF 가 없어도
    // MediaPipe 검출은 유지됨을 실험으로 확인했다. 기기 검증 전이라 기본은 기존대로 두고 환경변수로 켠다.
    const fastCapture = process.env.EXPO_PUBLIC_FAST_CAPTURE === '1';
    let frameNo = 0;

    const tick = async () => {
      if (cancelled || inFlight) return;
      inFlight = true;
      try {
        let image: string | undefined;
        const captureStartedAt = Date.now();
        let captureMs = 0;
        try {
          const cam = cameraRef.current;
          if (cam) {
            const snap = await cam.takePictureAsync({
              base64: true,
              quality: 0.4, // 분석엔 충분, 페이로드 줄임
              skipProcessing: fastCapture, // false: 분석 좌표계가 기기 회전과 일치하도록 처리(기본)
              shutterSound: false,
            });
            captureMs = Date.now() - captureStartedAt;
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
          const aiStartedAt = Date.now();
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
          // 3D 동작 비교 — 스킵 프레임도 시간 간격을 남기려고 그대로 넣는다(좌표 없으면 lm=null)
          motionRecorder.push(sessionId, {
            atMs: Date.now(),
            landmarks: r.landmarks,
            aspect: r.image_aspect_ratio,
            repCount: r.rep_count,
            lead: r.lunge?.lead_leg ?? null,
          });
          if (__DEV__ && ++frameNo % 5 === 1) {
            // 실기기 시험용 — 5프레임마다 캡처/AI 소요(ms)와 판정 결과를 Metro 터미널에 남긴다.
            // eslint-disable-next-line no-console
            console.log(
              `[frame ${frameNo}] capture=${captureMs}ms ai=${Date.now() - aiStartedAt}ms ` +
                `size=${Math.round(image.length / 1024)}KB fast=${fastCapture} ` +
                `success=${r.success} skip=${r.skip_reason ?? '-'} reps=${r.rep_count ?? '-'}`,
            );
          }
          setGotResponse(true);
          setAiError(false);
          if (r.lunge) setLunge(r.lunge);
          if (r.squat) setSquat(r.squat);
          if (r.success === false) {
            if (r.skip_reason !== 'RATE_LIMITED') {
              setCaptureMessage(r.lunge?.guidance ?? r.message ?? '전신이 보이도록 위치를 조정해주세요.');
              // RATE_LIMITED 는 건강한 세션에서도 나는 서버의 의도적 스킵이라 «안 보임» 으로 읽지 않는다.
              setLastSkip(r.skip_reason ?? null);
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
          setLastSkip(null);
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
          if (!cancelled) {
            setCaptureMessage('AI 연결을 확인하고 있습니다. 잠시 원위치에서 기다려주세요.');
            setAiError(true);
          }
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
        // 운동 중 ~3fps 로 takePictureAsync 를 계속 부르는데, 기본값(true)이면 매 촬영마다 셔터
        // 애니메이션(화면 번쩍임)이 재생된다. 분석용 프레임 캡처라 끈다(2026-10-06).
        animateShutter={false}
        pictureSize={pictureSize}
        onCameraReady={handleCameraReady}
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
        {/* 지금 뭘 해야 하는지 — 몸이 보이는지, 준비 자세를 인식했는지, 운동 중인지 */}
        <ReadinessBanner readiness={readiness} />
        {isLunge ? (
          <View style={styles.lungePanel}>
            <Text style={styles.lungeTitle}>전방 런지 · 횟수 측정</Text>
            {isRecording && <Text style={styles.lungeSides}>{lunge ? `왼쪽 ${lunge.left_reps}회 · 오른쪽 ${lunge.right_reps}회` : '좌우 횟수 확인 중...'}</Text>}
            {!!lunge?.unassigned_reps && <Text style={styles.guideRow}>이전 {lunge.unassigned_reps}회는 좌우 정보 없이 복원되었습니다.</Text>}
            <Text style={styles.guideRow}>운동이 끝나면 깊이·상체·보폭 점수를 리포트에서 확인할 수 있어요.</Text>
          </View>
        ) : (
        <View style={styles.syncGauge}>
          {/* 색: 정석(≥ good) 라임 · 교정 필요(≥ warn) 주황 · 위험 빨강 — 기준은 종목별 관리자 설정 */}
          <Zap size={14} color={isRecording && syncRate > 0 ? liveSyncColor : COLORS.textMuted} strokeWidth={2}
            fill={isRecording && syncRate > 0 ? liveSyncColor : 'transparent'} />
          <Text style={styles.syncLabel}>싱크로율</Text>
          <View style={styles.syncBarBg}>
            {isRecording && syncRate > 0 && (
              <View style={[styles.syncBarFill, { width: `${Math.min(100, syncRate)}%`, backgroundColor: liveSyncColor }]} />
            )}
          </View>
          <Text style={[styles.syncValue, isRecording && syncRate > 0 && { color: liveSyncColor }]}>
            {isRecording && syncRate > 0 ? `${syncRate}%` : '측정 전'}
          </Text>
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
            <Text style={styles.guideRow}>{isLunge ? '📐  완전한 측면(옆)에 카메라 고정 · 양쪽 무릎과 발끝이 보여야 합니다' : '📐  정면 기준 45° 위치에 카메라 고정'}</Text>
            {isLunge && <Text style={styles.guideRow}>🦵  다리를 앞뒤로 벌린 자세에서 무릎을 굽혔다 펴주세요</Text>}
            <Text style={styles.guideRow}>🧍  머리부터 발목까지 여백을 두고 촬영</Text>
            <Text style={styles.guideRow}>⏱️  시작과 종료 때 똑바로 선 자세를 2초 유지</Text>
            <Text style={styles.guideRow}>💡  밝은 조명, 단색 배경 권장</Text>
            <Text style={styles.guideRow}>🚫  무릎을 가리는 랙·기구와 여러 사람 환경은 피해주세요</Text>
          </View>
        )}

        {/* 횟수가 인식된 순간 «✓ N회» */}
        {repFlash != null && (
          <View pointerEvents="none" style={styles.repFlash}>
            <Text style={styles.repFlashText}>✓ {repFlash}회</Text>
          </View>
        )}

        {/* 3D 목각인형 시범 */}
        {demoOpen && !isRecording && (
          <View style={styles.demoPanel}>
            <View style={styles.demoHeader}>
              <Text style={styles.demoTitle}>{isLunge ? '전방 런지' : '스쿼트'} 3D 시범</Text>
              <TouchableOpacity onPress={() => setDemoOpen(false)} style={styles.demoClose} accessibilityLabel="시범 닫기">
                <X size={18} color={COLORS.text} strokeWidth={2} />
              </TouchableOpacity>
            </View>
            <MannequinDemo exercise={isLunge ? 'lunge' : 'squat'} />
          </View>
        )}

        {/* 하단: 3D 시범 + 운동 시작/종료 버튼 */}
        <View style={styles.bottomOverlay}>
          <View style={styles.bottomCenter}>
            <Text style={styles.recordHint}>
              {isRecording ? '운동 중... 끝나면 버튼을 눌러 종료하세요' : '버튼을 눌러 운동을 시작하세요'}
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

          {/* 3D 시범 열기 */}
          {!isRecording && <TouchableOpacity style={styles.refVideoBtn} activeOpacity={0.7} onPress={() => setDemoOpen((open) => !open)}>
            <Box size={18} color={COLORS.primary} strokeWidth={2} />
            <Text style={styles.refVideoText}>3D 시범</Text>
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
  repFlash: {
    position: 'absolute',
    top: '38%',
    alignSelf: 'center',
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 24,
    backgroundColor: 'rgba(76,175,80,0.92)',
  },
  repFlashText: { color: '#fff', fontSize: 40, fontWeight: '900' },
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
  syncBarBg: { flex: 1, height: 6, backgroundColor: COLORS.surfaceLight, borderRadius: 3, overflow: 'hidden' },
  syncBarFill: { height: 6, borderRadius: 3 },
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

  // 3D 시범 패널
  demoPanel: {
    position: 'absolute',
    left: SPACING.lg,
    right: SPACING.lg,
    top: '20%',
    height: 340,
    borderRadius: RADIUS.lg,
    overflow: 'hidden',
    backgroundColor: 'rgba(13,15,21,0.94)',
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  demoHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingLeft: SPACING.lg,
    paddingRight: SPACING.sm,
    paddingVertical: SPACING.sm,
    borderBottomWidth: 1,
    borderBottomColor: COLORS.divider,
  },
  demoTitle: { color: COLORS.text, fontSize: FONT_SIZE.md, fontWeight: '700' },
  demoClose: { padding: SPACING.xs },

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
