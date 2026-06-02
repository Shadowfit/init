# 대용량 실험용 합성 시딩 절차

RealMySQL 실험([`../../docs/portfolio/realmysql-experiments.md`](../../docs/portfolio/realmysql-experiments.md))용 pose_data 대용량 시딩.
**DAU 1,000 시나리오의 부분 시뮬레이션** — 로컬 디스크 제약상 ~375만 행(현실적 33-랜드마크 JSON). 절대 스케일 수치는 격리 환경 필요(§7.8 교훈).

## 전제
- `pose_data`에 템플릿 세션(예: 601)이 ~750행 존재 (없으면 1세션 실데이터 먼저 적재).
- docker `shadowfit-mysql` 가동 중.

## 1. 세션 시딩 (5,000개, 2026년 12개월 분산)

```bash
docker exec -i shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit' < seed_sessions.sql
```

## 2. pose_data 시딩 (템플릿 × 세션 cross join, 청크)

```bash
# 템플릿 테이블 (601의 750행)
docker exec shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -e "
DROP TABLE IF EXISTS _pose_template;
CREATE TABLE _pose_template AS
  SELECT timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message
  FROM pose_data WHERE session_id=601;"'

# 10청크 (id%10) × 500세션 × 750행 = 375만
for i in $(seq 0 9); do
  docker exec shadowfit-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" shadowfit -e \"
    INSERT INTO pose_data (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message, created_at)
    SELECT s.id, t.timestamp_sec, t.joint_coordinates, t.sync_rate, t.is_correct, t.feedback_message, s.start_time
    FROM _pose_template t
    CROSS JOIN (SELECT id, start_time FROM exercise_sessions WHERE reference_source='seed' AND id % 10 = $i) s;\""
  echo "청크 $i/9"
done
```

## 3. 통계 갱신 (EXPLAIN 정확도)

```bash
docker exec shadowfit-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -e "ANALYZE TABLE pose_data;"'
```

## 정리

```sql
DELETE FROM pose_data WHERE session_id IN (SELECT id FROM exercise_sessions WHERE reference_source='seed');
DELETE FROM exercise_sessions WHERE reference_source='seed';
DROP TABLE IF EXISTS _pose_template;
```

> 참고: `joint_coordinates`(2.3KB JSON)가 행 크기를 지배. **행 수 스케일 실험**(파티션 pruning·B-Tree)은 더미 JSON으로 별도 rig를 쓰면 행수/payload를 디커플링 가능(realmysql §0.3).
