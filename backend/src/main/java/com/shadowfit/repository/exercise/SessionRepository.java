package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session,Long> {
    // 소유권을 WHERE절에 박아 조회-후-검증(fetch-then-check) 대신 구조적으로 IDOR 차단.
    // 본인 세션이 아니거나 존재하지 않으면 똑같이 empty — "존재하지만 남의 것"이라는 정보를 노출 안 함.
    @Query("SELECT s FROM Session s JOIN FETCH s.exercise WHERE s.id = :sessionId AND s.member.id = :memberId")
    Optional<Session> findSessionWithExerciseByIdAndMemberId(@Param("sessionId") Long sessionId,
                                                              @Param("memberId") Long memberId);

    // 개별 세션 삭제(deleteSession) 전용 — exercise fetch join 불필요, 소유권만 WHERE절로 확인.
    Optional<Session> findByIdAndMemberId(Long sessionId, Long memberId);

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

    // 활동한 날짜 목록만 가져옴 — calculateConsecutiveDays 루프 N+1 방지.
    // 반환 타입은 java.sql.Date로 받는다 — List<LocalDate>로 바로 받으면 Spring Data의
    // ConversionService가 java.sql.Date -> LocalDate 컨버터를 못 찾아 ConverterNotFoundException.
    // 변환은 호출부(calculateConsecutiveDays)에서 Date.toLocalDate()로 처리.
    @Query("SELECT DISTINCT CAST(s.startTime AS date) FROM Session s " +
           "WHERE s.member.id = :memberId AND s.startTime BETWEEN :start AND :end")
    List<Date> findDistinctActiveDates(@Param("memberId") Long memberId,
                                       @Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    @Query("SELECT s FROM Session s JOIN FETCH s.exercise WHERE s.status = :status")
    List<Session> findByStatus(@Param("status") Status status);

    // 회원당 활성 세션 1개 제약 — MemberRepository.findByIdForUpdate로 잠근 뒤 체크해야
    // TOCTOU 없이 안전함(단독 호출 시엔 레이스 존재).
    boolean existsByMemberIdAndStatus(Long memberId, Status status);

    // 회원 탈퇴 시 pose_data 비동기 정리용 — session_id 목록만 가볍게 조회.
    // pose_data의 FK(CASCADE)를 파티셔닝 때문에 제거해서, 탈퇴로 세션이 사라지기 전에
    // 미리 확보해둬야 함 (docs/decisions/pose-data-partition-fk-tradeoff.md).
    @Query("SELECT s.id FROM Session s WHERE s.member.id = :memberId")
    List<Long> findIdsByMemberId(@Param("memberId") Long memberId);
}
