package com.shadowfit.repository.report;

import com.shadowfit.model.report.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    Optional<WeeklyReport> findByMemberIdAndPeriodStart(Long memberId, LocalDate periodStart);
}
