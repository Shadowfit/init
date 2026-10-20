import THREE_SOURCE from './threeSource';
import GLTF_LOADER_SOURCE from './gltfLoader';
import BODY_GLB_BASE64 from './bodyModel';
import { SCENE_TEMPLATE } from './sceneHtml';

export type MannequinExercise = 'squat' | 'lunge';

/** 시범 종목을 넣어 WebView/iframe 에 그대로 띄울 HTML 한 장을 만든다. */
export function buildMannequinHtml(exercise: MannequinExercise): string {
  // 치환값에 `$&` 같은 패턴이 섞여 있어도(three.min.js) 그대로 들어가도록 함수 치환을 쓴다.
  return SCENE_TEMPLATE
    .replace('/*__THREE__*/', () => THREE_SOURCE)
    .replace('/*__GLTF__*/', () => GLTF_LOADER_SOURCE)
    .replace('/*__BODY__*/', () => `window.__BODY_GLB__ = ${JSON.stringify(BODY_GLB_BASE64)};`)
    .replace('/*__CONFIG__*/', () => `window.__EXERCISE__ = ${JSON.stringify(exercise)};`);
}
