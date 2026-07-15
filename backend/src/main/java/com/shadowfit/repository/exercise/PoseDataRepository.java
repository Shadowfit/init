package com.shadowfit.repository.exercise;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.model.exercise.PoseData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PoseDataRepository extends JpaRepository<PoseData, Long> {
    // 리포트 worst 구간 계산에 필요한 3컬럼만 조회. joint_coordinates(2.3KB JSON) 제외 → off-page I/O 회피.
    @Query("SELECT new com.shadowfit.dto.report.PoseFrameProjection(" +
           "p.timestampSec, p.syncRate, p.feedbackMessage) " +
           "FROM PoseData p WHERE p.session.id = :sessionId ORDER BY p.timestampSec ASC")
    List<PoseFrameProjection> findFramesBySessionId(@Param("sessionId") Long sessionId);
}