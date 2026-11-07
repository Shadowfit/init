import THREE_SOURCE from './threeSource';
import GLTF_LOADER_SOURCE from './gltfLoader';
import BODY_GLB_BASE64 from './bodyModel';
import { SCENE_TEMPLATE } from './sceneHtml';
import { COMPARE_EXT } from './compareExt';

export type MannequinExercise = 'squat' | 'lunge';

/**
 * 시범 종목을 넣어 WebView/iframe 에 그대로 띄울 HTML 한 장을 만든다.
 * compare=true 이면 3D 운동 비교용 확장(compareExt.ts)이 들어간다 — 기준 시범 관절 추출, 사용자 골격,
 * 동기화 재생. 켜지 않으면 기존 시범 화면과 완전히 같다(확장 자리는 빈 문자열).
 */
export function buildMannequinHtml(exercise: MannequinExercise, options: { compare?: boolean } = {}): string {
  const compare = options.compare === true;
  // 치환값에 `$&` 같은 패턴이 섞여 있어도(three.min.js) 그대로 들어가도록 함수 치환을 쓴다.
  return SCENE_TEMPLATE
    .replace('/*__THREE__*/', () => THREE_SOURCE)
    .replace('/*__GLTF__*/', () => GLTF_LOADER_SOURCE)
    .replace('/*__BODY__*/', () => `window.__BODY_GLB__ = ${JSON.stringify(BODY_GLB_BASE64)};`)
    .replace('/*__CONFIG__*/', () =>
      `window.__EXERCISE__ = ${JSON.stringify(exercise)};` + (compare ? 'window.__COMPARE__ = true;' : ''))
    .replace('/*__EXT__*/', () => (compare ? COMPARE_EXT : ''));
}
