import { createAudioPlayer, setAudioModeAsync, type AudioPlayer } from 'expo-audio';
import type { BeepKind } from '@/utils/beep';

/**
 * 비프음 재생 — expo-audio 를 감싼다. 언제·무엇을 울릴지는 utils/beep.ts 가 정하고, 여기는 «울린다» 만 한다.
 *
 * 플레이어를 운동 화면이 열릴 때 미리 만들어 둔다. 울릴 때마다 만들면 첫 소리가 파일을 읽는 시간만큼 늦고,
 * 비프음은 «바로 그 순간» 이 중요하다.
 *
 * <b>소리가 안 나도 운동을 막지 않는다.</b> 오디오 초기화·재생 실패는 전부 삼킨다 — 화면 문구와 진동이 같은 안내를 한다.
 */

// 상대 경로다. 이 프로젝트가 자산(`app/_layout.tsx` 의 폰트)을 부르는 방식이고, Metro 의 `@/` 별칭 해석에 기대지 않는다.
const SOURCES: Record<BeepKind, number> = {
  success: require('../assets/sounds/beep-success.wav'),
  warning: require('../assets/sounds/beep-warning.wav'),
  notice: require('../assets/sounds/beep-notice.wav'),
};

const KINDS = Object.keys(SOURCES) as BeepKind[];

let players: Partial<Record<BeepKind, AudioPlayer>> = {};
let ready = false;

/** 오디오 모드를 맞추고 플레이어 3개를 만든다. 여러 번 불러도 한 번만 한다. */
export async function initBeeps(): Promise<void> {
  if (ready) return;
  try {
    await setAudioModeAsync({
      // 효과음이라 사용자가 틀어 둔 음악을 끊지 않는다(iOS·Android 공통).
      interruptionMode: 'mixWithOthers',
      // 무음 스위치를 켜 둬도 울린다(expo-audio 기본값). 끄고 싶으면 마이페이지의 «효과음 안내» 토글을 쓴다.
      playsInSilentMode: true,
      allowsRecording: false,
      shouldPlayInBackground: false,
    });
    for (const kind of KINDS) players[kind] = createAudioPlayer(SOURCES[kind]);
    ready = true;
  } catch {
    players = {};
  }
}

/** 소리를 처음부터 한 번 울린다. 아직 준비 전이면 아무 일도 하지 않는다. */
export function playBeep(kind: BeepKind): void {
  const player = players[kind];
  if (!player) return;
  try {
    // 이전 재생이 끝난 자리에 멈춰 있으므로 처음으로 돌린 뒤 울린다(같은 플레이어를 재사용).
    void player.seekTo(0).catch(() => {});
    player.play();
  } catch {
    // 재생 실패는 무시한다(위 머리말).
  }
}

/** 화면을 떠날 때 플레이어를 해제한다. */
export function releaseBeeps(): void {
  for (const kind of KINDS) {
    try {
      players[kind]?.remove();
    } catch {
      // 이미 해제됐으면 무시
    }
  }
  players = {};
  ready = false;
}
