#!/usr/bin/env bash
# ④ 버퍼풀 실험 — 작업셋 vs innodb_buffer_pool_size 에 따른 디스크 읽기·hit율
# RealMySQL 실험 (docs/portfolio/realmysql-experiments.md §4 ④ Ch.4)
#
# 타깃: pose_data_scale ~8,256만 행(~10.4GB) ≫ 버퍼풀 128MB (약 80배).
# 가설: ① 작업셋이 풀보다 크면 warm 재실행도 디스크 읽기가 줄지 않는다(캐시 무력, hit율 낮음).
#       ② 작업셋이 풀보다 작으면 cold 1회 후 warm 은 디스크 읽기 ~0(완전 캐시, hit율 ~100%).
# 지표: Innodb_buffer_pool_reads(물리=디스크) vs _read_requests(논리=요청).
#       hit율 = (요청-디스크)/요청. 쿼리 전후 글로벌 카운터 델타로 그 쿼리 몫만 분리.
# 주의: 글로벌 카운터라 동시 부하 없을 때(idle lab) 측정. 1쿼리=델타.
set -u
PW=1234
C=shadowfit-mysql
DB(){ docker exec $C mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }
gstat(){ DB -N -e "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='$1'"; }

POOL_MB=$(DB -N -e "SELECT @@innodb_buffer_pool_size/1024/1024")
echo "## [0] 환경 — 버퍼풀 ${POOL_MB} MB vs pose_data_scale"
DB -t -e "SELECT table_rows, ROUND((data_length+index_length)/1024/1024,0) total_mb
          FROM information_schema.tables WHERE table_schema='shadowfit' AND table_name='pose_data_scale';"

# $1=라벨  $2=쿼리 . 전후 카운터 델타 + 시간(ms) 출력.
# ⚠️ 순차 스캔은 read-ahead(비동기 선읽기)로 페이지를 당겨오므로 _reads(동기 미스)만 보면
#    hit율이 거짓 99%가 된다. 진짜 물리 I/O = reads + read_ahead, 그리고 Innodb_data_read(바이트).
measure(){
  local label="$1" q="$2"
  local rq0 rd0 ra0 by0 rq1 rd1 ra1 by1 t0 t1
  rq0=$(gstat Innodb_buffer_pool_read_requests); rd0=$(gstat Innodb_buffer_pool_reads)
  ra0=$(gstat Innodb_buffer_pool_read_ahead);    by0=$(gstat Innodb_data_read)
  t0=$(date +%s%N)
  DB -N -e "$q" >/dev/null
  t1=$(date +%s%N)
  rq1=$(gstat Innodb_buffer_pool_read_requests); rd1=$(gstat Innodb_buffer_pool_reads)
  ra1=$(gstat Innodb_buffer_pool_read_ahead);    by1=$(gstat Innodb_data_read)
  local drq=$((rq1-rq0)) drd=$((rd1-rd0)) dra=$((ra1-ra0)) ms=$(( (t1-t0)/1000000 ))
  local phys=$((drd+dra)) mb=$(awk "BEGIN{printf \"%.0f\", ($by1-$by0)/1048576}")
  local hit="n/a"
  if [ "$drq" -gt 0 ]; then hit=$(awk "BEGIN{printf \"%.1f%%\", (1-$phys/$drq)*100}"); fi
  printf "  %-22s %7d ms | 논리 %9d | 물리(미스+선읽기) %7d | 디스크 %5d MB | hit %s\n" \
    "$label" "$ms" "$drq" "$phys" "$mb" "$hit"
}

echo
echo "## [1] 작업셋 ≫ 풀 — 한 달 파티션 풀스캔(~8M행, ~1GB ≫ 128MB)"
echo "SUM(sync_rate): 클러스터드 인덱스 리프 전체 읽음(sync_rate 비인덱스). cold→warm 디스크읽기 거의 안 줆 기대."
PART_Q="SELECT SUM(sync_rate) FROM pose_data_scale WHERE created_at >= '2026-06-01' AND created_at < '2026-07-01'"
measure "big cold (1회차)" "$PART_Q"
measure "big warm (2회차)" "$PART_Q"
measure "big warm (3회차)" "$PART_Q"
echo "  → 1GB 작업셋이 128MB 풀에 안 들어가 → warm 도 디스크읽기 계속(hit율 낮음). 캐시가 무력."

echo
echo "## [2] 작업셋 < 풀 — 좁은 PK 범위(~5만행, 수 MB < 128MB)"
echo "같은 쿼리 cold→warm. 작은 작업셋이라 1회차 후 풀에 상주 → warm 디스크읽기 ~0 기대."
SMALL_Q="SELECT SUM(sync_rate) FROM pose_data_scale WHERE id BETWEEN 5000000 AND 5050000"
measure "small cold (1회차)" "$SMALL_Q"
measure "small warm (2회차)" "$SMALL_Q"
measure "small warm (3회차)" "$SMALL_Q"
echo "  → 작업셋 < 풀 → warm 은 hit ~100%, 디스크읽기 ~0. 핫 데이터는 메모리 상주."

echo
echo "## [3] 전체 버퍼풀 상태 (sys.innodb_buffer_stats / 누적 hit율)"
DB -t -e "SELECT object_schema, object_name, allocated, pages
          FROM sys.innodb_buffer_stats_by_table
          WHERE object_schema='shadowfit' ORDER BY pages DESC LIMIT 5;" 2>/dev/null \
  || DB -t -e "SHOW GLOBAL STATUS WHERE Variable_name IN
        ('Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads','Innodb_buffer_pool_pages_data','Innodb_buffer_pool_pages_total');"

echo
echo "## [4] 서사 (사이징 판단)"
echo "  pose_data 류 시계열 raw 가 버퍼풀을 압도하면, 리포트 같은 넓은 스캔은 디스크 바운드가 된다."
echo "  → ⓐ 작업셋(핫 데이터)만 풀에 들어가도록 쿼리/보존 설계(raw 단기화·파티션 만료) ⓑ 풀 증설."
echo "  페이지네이션 cold/warm(②c)·시딩 가속(버퍼풀 128MB→2GB)과 같은 뿌리 — 작업셋 vs 메모리."

echo
echo "DONE  (실측치는 docs/portfolio/realmysql-experiments.md §4 ④ '결과 (버퍼풀)' 에 박제)"
