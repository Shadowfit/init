package com.shadowfit.repository.report;

import com.shadowfit.model.report.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    Optional<WeeklyReport> findByMemberIdAndPeriodStart(Long memberId, LocalDate periodStart);

    /**
     * 종료 상태 전이는 «PENDING 일 때만» 조건부 UPDATE 다 — 아웃박스 {@code markSent} 의 CAS 펜싱과 같은 이유.
     * 두 발행기가 lease 회수 경합으로 같은 행을 처리하면(다중 인스턴스·lease 만료) 읽고-바꾸기는 나중 커밋이 먼저 것을
     * 덮는다. 0 행이면 이미 누가 끝냈다는 뜻이고 호출자는 그걸 성공으로 흡수한다(재배달 멱등).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WeeklyReport r
               SET r.summarySource = com.shadowfit.model.report.WeeklyReportSource.LLM,
                   r.summary = :summary,
                   r.citedMetrics = :citedMetrics,
                   r.generationModel = :model,
                   r.promptVersion = :promptVersion,
                   r.generatedAt = :at
             WHERE r.id = :id
               AND r.summarySource = com.shadowfit.model.report.WeeklyReportSource.PENDING
            """)
    int completeWithLlmIfPending(@Param("id") Long id, @Param("summary") String summary,
                                 @Param("citedMetrics") String citedMetrics, @Param("model") String model,
                                 @Param("promptVersion") String promptVersion, @Param("at") LocalDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WeeklyReport r
               SET r.summarySource = com.shadowfit.model.report.WeeklyReportSource.TEMPLATE_FALLBACK,
                   r.fallbackReason = :reason,
                   r.generationModel = :model,
                   r.promptVersion = :promptVersion,
                   r.generatedAt = :at
             WHERE r.id = :id
               AND r.summarySource = com.shadowfit.model.report.WeeklyReportSource.PENDING
            """)
    int fallBackIfPending(@Param("id") Long id, @Param("reason") String reason, @Param("model") String model,
                          @Param("promptVersion") String promptVersion, @Param("at") LocalDateTime at);
}
