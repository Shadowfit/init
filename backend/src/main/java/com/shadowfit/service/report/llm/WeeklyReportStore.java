package com.shadowfit.service.report.llm;

import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.report.WeeklyReport;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.repository.report.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@code weekly_reports} 행의 트랜잭션 경계 — 생성 요청(행 + 아웃박스 한 트랜잭션)과 종료 기록.
 * 별도 빈인 이유는 {@code OutboxEventStore} 와 같다(#175 자기호출 프록시 우회).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReportStore {

    private final WeeklyReportRepository weeklyReportRepository;
    private final OutboxEventRepository outboxEventRepository;

    /**
     * PENDING 행 INSERT + 아웃박스 {@code GENERATE_WEEKLY_REPORT} — 한 트랜잭션. 유니크 충돌은 호출자가 잡아
     * 기존 행을 읽는다(같은 주를 두 요청이 동시에 처음 열었을 때 — 한쪽만 이긴다).
     *
     * <p>REQUIRES_NEW 인 이유: 호출자(조회)는 readOnly 트랜잭션일 수 있고, 충돌로 롤백돼도 조회 자체는 살아야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WeeklyReport requestGeneration(Member member, LocalDate periodStart) {
        WeeklyReport saved = weeklyReportRepository.saveAndFlush(WeeklyReport.pending(member, periodStart));
        outboxEventRepository.save(OutboxEvent.generateWeeklyReport(saved.getId(), CorrelationIds.current()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<WeeklyReport> find(Long memberId, LocalDate periodStart) {
        return weeklyReportRepository.findByMemberIdAndPeriodStart(memberId, periodStart);
    }

    @Transactional(readOnly = true)
    public Optional<WeeklyReport> findById(Long id) {
        return weeklyReportRepository.findById(id);
    }

    /** @return 실제로 바뀌었나 — 이미 종료 상태면 false(재배달·회수분은 여기서 멱등하게 흡수된다). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeWithLlm(Long id, String summary, String citedMetricsJson, String model, String promptVersion) {
        return weeklyReportRepository.findById(id)
                .map(r -> r.completeWithLlm(summary, citedMetricsJson, model, promptVersion, LocalDateTime.now()))
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fallBack(Long id, String reason, String model, String promptVersion) {
        return weeklyReportRepository.findById(id)
                .map(r -> r.fallBack(reason, model, promptVersion, LocalDateTime.now()))
                .orElse(false);
    }
}
