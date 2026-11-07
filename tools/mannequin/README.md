# 3D 시범 인체 모델 다시 만들기

`frontend/components/exercise/mannequin/bodyModel.ts`(GLB base64)와 `gltfLoader.ts`는 이 폴더의 스크립트로 만든 자동 생성 파일입니다.

## 필요한 것
- Python 3.13 + `pip install bpy==5.2.2` (Blender를 파이썬 모듈로 실행)
- MPFB 2 소스: `git clone https://github.com/makehumancommunity/mpfb2` → `MPFB_SRC=<clone>/src/mpfb`
- Node 18+ : `npm i esbuild@0.24.0 three@0.149.0 @gltf-transform/core@4.1.1 @gltf-transform/extensions@4.1.1 @gltf-transform/functions@4.1.1`

## 순서
```
export MPFB_SRC=/path/to/mpfb2/src/mpfb
python build_body.py        # 근육형 남성 체형 생성 → 체형 굳힘 → game_engine 뼈대 맞춤 → human.blend
python bake_masks.py        # 근육 부위 마스크 8개를 정점 속성 _MASKA/_MASKB 로 굽고 body.glb 내보내기
node optimize_glb.mjs body.glb body.q.glb   # 안 쓰는 색 속성 제거 + 양자화 (1.46MB → 0.60MB)
node bundle_gltfloader.mjs  # GLTFLoader 를 전역 THREE 용 IIFE 로 묶기 → gltfloader.min.js
```
그다음 `body.q.glb`를 base64로 `bodyModel.ts`에, `gltfloader.min.js`를 JSON 문자열로 `gltfLoader.ts`에 넣습니다 (파일 머리 주석 형식 그대로).

## 마스크 순서
- `_MASKA` = (대퇴사두근, 대둔근, 햄스트링, 내전근)
- `_MASKB` = (종아리, 척추기립근, 복근, 어깨)

마스크는 뼈 가중치·표면 방향·높이로 계산한 **부위 영역**입니다. 해부도처럼 근육 하나하나의 경계를 따른 것은 아닙니다.

## 라이선스
- MakeHuman / MPFB 2 에셋(기본 메시·타깃·뼈대): **CC0** — 출처 표기 의무 없음. https://static.makehumancommunity.org/about/license.html
- MPFB 2 코드(GPL)는 모델을 만드는 데만 쓰고 앱에는 들어가지 않습니다.
- three.js / GLTFLoader: MIT License.
