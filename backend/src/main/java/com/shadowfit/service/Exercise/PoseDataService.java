package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoseDataService {

    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final ExerciseReferenceRepository referenceRepository;
    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_POSE_SQL =
            "INSERT INTO pose_data " +
            "(session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

    /**
     * [실시간 저장] FastAPI가 주기적으로 쏴주는 분석 좌표 데이터 묶음을 DB에 저장합니다.
     *
     * JPA saveAll 은 PoseData.id 가 IDENTITY 라 Hibernate batch insert 가 비활성(개별 INSERT N방).
     * 부하 테스트(§7.5)에서 동시성 100에 p99 4.6s·throughput 천장 확인 → JdbcTemplate.batchUpdate
     * 로 multi-row INSERT 단일화. created_at 은 DB DEFAULT CURRENT_TIMESTAMP 에 위임.
     */
    @Transactional
    public void savePoseDataBatch(Long sessionId, List<com.shadowfit.grpc.PoseDataRequest> grpcList) {
        if (grpcList == null || grpcList.isEmpty()) return;

        // 세션 존재 검증 — pose_data는 파티셔닝을 위해 FK(CASCADE)를 제거해서(2026-07-20,
        // docs/decisions/pose-data-partition-fk-tradeoff.md), 이 체크가 DB의 백업이 아니라
        // 참조무결성을 보장하는 유일한 장치가 됨. 기존 SESSION_NOT_FOUND 계약도 그대로 유지.
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        jdbcTemplate.batchUpdate(INSERT_POSE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PoseDataRequest grpc = grpcList.get(i);
                ps.setLong(1, sessionId);
                ps.setDouble(2, grpc.getTimestampSec());
                ps.setString(3, grpc.getJointCoordinates());
                ps.setDouble(4, grpc.getSyncRate());
                ps.setBoolean(5, grpc.getSyncRate() >= 40.0); // 40점 기준 (수정 가능)
                ps.setString(6, grpc.getFeedbackMessage());
            }

            @Override
            public int getBatchSize() {
                return grpcList.size();
            }
        });

        log.info("세션 {} : 포즈 데이터 {}개 일괄 저장 성공", sessionId, grpcList.size());
    }

    /**
     * [관리자용] AI가 유튜브에서 추출한 '정석 기준 좌표'를 DB에 저장합니다.
     */
    @Transactional
    @CacheEvict(cacheNames = "exerciseReferences", key = "#exerciseId")
    public void saveReferencePoses(Long exerciseId, List<com.shadowfit.grpc.PoseDataRequest> grpcList) {
        if (grpcList == null || grpcList.isEmpty()) return;

        Exercise exercise = exercisesRepository.findByIdCached(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        List<ExerciseReference> referenceEntities = grpcList.stream()
                .map(grpc -> ExerciseReference.builder()
                        .exercise(exercise)
                        .timestampSec(grpc.getTimestampSec())
                        .jointCoordinates(grpc.getJointCoordinates())
                        .build())
                .collect(Collectors.toList());

        referenceRepository.saveAll(referenceEntities);
        log.info("운동 ID {} : 기준 좌표 {}개 등록 완료", exerciseId, referenceEntities.size());
    }
}