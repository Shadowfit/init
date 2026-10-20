-- 관리자 mp4 업로드 → 기준 좌표 추출 (2026-09-17 사용자 confirm: 공유 볼륨 전달 · exercises 컬럼 1개 · 파일 보관/교체)
--
-- «현재 정답지(exercise_references)가 어느 영상에서 나왔나» 를 남기는 컬럼이다. 추출은 결정적이지 않아
-- (#224 — 같은 영상도 실행마다 ±1 프레임·±1.3 score) 재추출은 «같은 정답지를 다시 만드는 일» 이 아닌데,
-- 원본 영상마저 안 남기면 나중에 「정답지를 바꿨나?」를 되짚을 근거가 없다.
--
-- 값은 저장 루트에 대한 **상대 경로**(`{exercise_id}/{uuid}.mp4`)다. 절대 경로를 박지 않는 이유 — Spring 이 쓰는
-- 경로(호스트 bootRun 이면 E:\...)와 AI 가 읽는 경로(컨테이너 /data/...)가 문자열로는 다를 수 있어서, 루트는
-- 각자 설정(reference-video.dir / ai-dir)에서 붙인다. NULL = 업로드로 등록된 영상이 없다(V4 시드 스쿼트가 그렇다).
ALTER TABLE exercises
    ADD COLUMN reference_video_path VARCHAR(500) NULL
        COMMENT '기준 좌표를 뽑은 업로드 영상 — 저장 루트 기준 상대 경로, NULL 이면 업로드 이력 없음' AFTER preferred_url;
