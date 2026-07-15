package com.shadowfit.repository.report;

import com.shadowfit.model.report.DailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface DailyLogRepository extends JpaRepository<DailyLog,Long> {
    Optional<DailyLog> findByMemberIdAndLogDate(Long memberId, LocalDate logDate);

    // 같은 날 두 세션이 동시에 종료돼도 lost-update가 안 생김 — INSERT/UPDATE 판단과 누적을
    // DB 한 문장(원자 연산)으로 처리. JPA save()로 INSERT 실패를 catch해 재시도하는 방식은
    // Hibernate 세션이 flush 실패로 손상돼 같은 트랜잭션에서 후속 쿼리가 깨짐(실측으로 확인,
    // DailyLogServiceConcurrencyTest 최초 실패: "don't flush the Session after an exception occurs")
    // — 그래서 네이티브 upsert 한 문장으로 우회.
    @Modifying
    @Query(value = "INSERT INTO daily_logs (member_id, log_date, total_exercise_time, total_calories) " +
                   "VALUES (:memberId, :logDate, :addTime, :addCalories) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "total_exercise_time = total_exercise_time + VALUES(total_exercise_time), " +
                   "total_calories = total_calories + VALUES(total_calories)",
           nativeQuery = true)
    void upsertStats(@Param("memberId") Long memberId, @Param("logDate") LocalDate logDate,
                      @Param("addTime") int addTime, @Param("addCalories") BigDecimal addCalories);
}
