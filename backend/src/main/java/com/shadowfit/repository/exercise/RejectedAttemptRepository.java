package com.shadowfit.repository.exercise;

import com.shadowfit.dto.report.RejectedAttemptProjection;
import com.shadowfit.model.exercise.RejectedAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * «미인정 시도»(V35) 조회. 쓰기는 {@code RejectedAttemptService} 의 JdbcTemplate(멱등 INSERT)가 한다.
 *
 * <p>{@code pose_data} 와 달리 파티션 표가 아니라 {@code created_at} 앵커가 필요 없다 — {@code session_id} 가
 * {@code uk_rejected_attempt} 의 선두 컬럼이라 세션 조회는 그 키를 탄다.
 */
@Repository
public interface RejectedAttemptRepository extends JpaRepository<RejectedAttempt, Long> {

    /**
     * 세션의 미인정 시도 요약, 시간 오름차순(동률이면 번호순). 세션 완료 시 싱크 통계·리포트 precompute 가 쓴다.
     * {@code joint_coordinates} 는 싣지 않는다({@link RejectedAttemptProjection}).
     */
    @Query("SELECT new com.shadowfit.dto.report.RejectedAttemptProjection(" +
           "r.attemptNo, r.reason, r.syncRate, r.minKneeAngle, r.torsoTiltDeg, r.timestampSec) " +
           "FROM RejectedAttempt r WHERE r.session.id = :sessionId " +
           "ORDER BY r.timestampSec ASC, r.attemptNo ASC")
    List<RejectedAttemptProjection> findSummariesBySessionId(@Param("sessionId") Long sessionId);

    /** 재부착 시 AI 에 주입할 미인정 시도 수 — {@code ReattachRequest.initial_rejected_count}. */
    long countBySessionId(Long sessionId);

    /** 재전송 판별 — 이미 있으면 INSERT 를 건너뛰고 «중복» 으로 센다(경합은 ODKU 가 흡수한다). */
    boolean existsBySessionIdAndAttemptNo(Long sessionId, Integer attemptNo);
}
