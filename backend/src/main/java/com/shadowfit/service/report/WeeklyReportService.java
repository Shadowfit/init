package com.shadowfit.service.report;

import com.shadowfit.dto.report.weekly.WeeklyReportResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.report.WeeklyReport;
import com.shadowfit.model.report.WeeklyReportSource;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.report.llm.WeeklyReportStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 끝난 주의 리포트 조회 — 템플릿 요약은 즉시, LLM 문장은 «있으면 같이, 없으면 만들라고 걸어두고» (report-generation-llm.md §14-2 B-a+a-1).
 *
 * <p>일부러 트랜잭션이 아니다: 집계 조회(readOnly)와 PENDING 행 INSERT(REQUIRES_NEW)가 각자 돈다 — 첫 조회가 LLM 을
 * 기다리지 않는 것이 §9 의 모양이고, INSERT 가 유니크에 져도 조회는 살아야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportService {

    private final WeeklySummaryService weeklySummaryService;
    private final WeeklyReportStore weeklyReportStore;
    private final MemberRepository memberRepository;

    /**
     * @param anyDayOfWeek 그 주의 아무 날. null 이면 지난주. 이번 주·미래 주는 400 — LLM 문장은 끝난 주에만 만든다.
     */
    public WeeklyReportResponseDto getWeeklyReport(Long memberId, LocalDate anyDayOfWeek) {
        LocalDate today = LocalDate.now();
        LocalDate base = anyDayOfWeek != null ? anyDayOfWeek : today.minusWeeks(1);
        LocalDate start = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusWeeks(1);
        if (end.isAfter(today)) { // end 는 배타적 상한(다음 주 월요일) — 오늘이 그 날이면 끝난 주다
            throw new BusinessException(ErrorCode.WEEKLY_REPORT_WEEK_NOT_COMPLETED);
        }

        WeeklyReport report = weeklyReportStore.find(memberId, start).orElseGet(() -> request(memberId, start));
        var summary = weeklySummaryService.getWeeklySummary(memberId, start);
        String aiSummary = report.getSummarySource() == WeeklyReportSource.LLM ? report.getSummary() : null;
        return new WeeklyReportResponseDto(start, end, summary, aiSummary, report.getSummarySource(), report.getGeneratedAt());
    }

    private WeeklyReport request(Long memberId, LocalDate start) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        try {
            return weeklyReportStore.requestGeneration(member, start);
        } catch (DataIntegrityViolationException e) {
            // 같은 주를 두 요청이 동시에 처음 열었다 — 진 쪽은 이긴 쪽의 행을 읽는다.
            return weeklyReportStore.find(memberId, start)
                    .orElseThrow(() -> new IllegalStateException("유니크 충돌 뒤 행이 없다 — member=" + memberId + " week=" + start));
        }
    }
}
