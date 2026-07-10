package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session,Long> {
    @Query("SELECT s FROM Session s JOIN FETCH s.exercise WHERE s.id = :sessionId")
    Optional<Session> findSessionWithExerciseById(@Param("sessionId") Long sessionId);

    Optional<Session> findFirstByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
            Long memberId, Long exerciseId, Status status
    );

    List<Session> findByMemberIdAndStartTimeBetween(Long memberId, LocalDateTime start, LocalDateTime end);

    // exercise를 fetch join해서 한 방에 가져옴 — getWeeklyActivity N+1 방지
    @Query("SELECT s FROM Session s JOIN FETCH s.exercise " +
           "WHERE s.member.id = :memberId AND s.startTime BETWEEN :start AND :end")
    List<Session> findWeeklySessionsWithExercise(@Param("memberId") Long memberId,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    // 활동한 날짜 목록만 가져옴 — calculateConsecutiveDays 루프 N+1 방지
    @Query("SELECT DISTINCT CAST(s.startTime AS date) FROM Session s " +
           "WHERE s.member.id = :memberId AND s.startTime BETWEEN :start AND :end")
    List<LocalDate> findDistinctActiveDates(@Param("memberId") Long memberId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Query("SELECT s FROM Session s JOIN FETCH s.exercise WHERE s.status = :status")
    List<Session> findByStatus(@Param("status") Status status);
}
