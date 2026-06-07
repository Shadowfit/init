#!/usr/bin/env bash
# ① JSON 트림 33→13 — joint_coordinates 저장 비용 절감 실측
# RealMySQL 실험 (docs/portfolio/realmysql-experiments.md §4 ④ Ch.15)
#
# 타깃: _landmarks_to_json(ai-server pose.py:93)이 MediaPipe 33개 landmark 전부 저장.
#   스쿼트 분석이 실제 쓰는 건 13개(ai-server constants.py LANDMARK)뿐 → 20개는 죽은 저장.
# 가설: 사용 13개만 남기면 joint_coordinates 평균 길이가 ~60% 줄어든다(구조적 절감).
# 한계: rig JSON 은 단일 템플릿 복제라 값 균일 — 하지만 트림은 "키/원소 수"라 값 무관하게 유효.
#       z(3D 각도)·visibility(추적신뢰도) 실사용이라 키는 못 줄임 → 13 landmark × 4 key 가 천장.
# substrate: _pose_template(750행, 현실 33-landmark JSON, ~2.3KB).
set -u
PW=1234
C=shadowfit-mysql
DB(){ docker exec $C mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }

# 스쿼트 사용 13개 landmark 인덱스 (constants.py LANDMARK)
USED="0 11 12 13 14 15 16 23 24 25 26 27 28"
# 13개를 JSON_ARRAY(JSON_EXTRACT(...)) 로 추출하는 식 빌드
TRIM_EXPR="JSON_ARRAY("
for i in $USED; do TRIM_EXPR+="JSON_EXTRACT(joint_coordinates,'\$.landmarks[$i]'),"; done
TRIM_EXPR="${TRIM_EXPR%,})"

echo "## [0] substrate — _pose_template (현실 33-landmark JSON)"
DB -t -e "SELECT COUNT(*) rows, JSON_LENGTH(joint_coordinates,'\$.landmarks') landmarks_per_row,
                 JSON_KEYS(JSON_EXTRACT(joint_coordinates,'\$.landmarks[0]')) keys_per_landmark
          FROM _pose_template LIMIT 1;"

echo
echo "## [1] 트림 33→13 — joint_coordinates 평균 길이 before/after"
DB -t -e "
SELECT
  AVG(LENGTH(joint_coordinates))            AS avg_33_full_bytes,
  AVG(LENGTH($TRIM_EXPR))                    AS avg_13_trim_bytes,
  ROUND(100*(1 - AVG(LENGTH($TRIM_EXPR))/AVG(LENGTH(joint_coordinates))),1) AS reduction_pct
FROM _pose_template;"

echo
echo "## [2] 스케일 투영 (현 pose_data 기준)"
DB -t -e "
SELECT table_rows,
       ROUND(data_length/1024/1024/1024,1)               AS data_gb_now,
       ROUND(data_length/1024/1024/1024*0.39,1)           AS data_gb_trimmed_est
FROM information_schema.tables WHERE table_schema='shadowfit' AND table_name='pose_data';"

echo
echo "## [3] 캐비엇"
echo "  - z·visibility 실사용 → 키는 못 줄임. 13 landmark × 4 key 가 트림 천장."
echo "  - rig JSON 은 index 필드 없음(실코드 _landmarks_to_json 은 index 키 포함) → 실제 절감 ≥ 측정치."
echo "  - 트림은 국소 미봉책. 진짜 해법은 raw→buffer/serving 분리(db-deep-dive §2-0)."
echo "  - generated column 선택도 실험은 미수행(rig 값 분포 균일 — 정직 캐비엇 §5)."

echo
echo "DONE  (실측치는 docs/portfolio/realmysql-experiments.md §4 ④ '결과 (트림 33→13)' 에 박제)"
