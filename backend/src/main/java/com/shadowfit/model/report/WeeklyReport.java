package com.shadowfit.model.report;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주간 리포트 LLM 문장 한 행 = 회원 × 주 (report-generation-llm.md §14-1 A-a, V21).
 * 통계는 저장하지 않는다 — 조회 시 {@code WeeklySummaryService} 가 계산한다. 여기 있는 건 생성된 문장과
 * 그때 인용한 숫자의 스냅샷뿐이다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(exclude = "member")
// V21 의 uk_weekly_reports_member_period 와 짝 — 테스트(H2, ddl-auto)는 이 애노테이션으로 스키마를 만든다.
@Table(name = "weekly_reports",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "period_start"}))
public class WeeklyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_source", nullable = false, length = 20)
    private WeeklyReportSource summarySource;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "cited_metrics", columnDefinition = "json")
    private String citedMetrics;

    @Column(name = "generation_model", length = 100)
    private String generationModel;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "fallback_reason", length = 100)
    private String fallbackReason;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    public static WeeklyReport pending(Member member, LocalDate periodStart) {
        return WeeklyReport.builder()
                .member(member)
                .periodStart(periodStart)
                .periodEnd(periodStart.plusWeeks(1))
                .summarySource(WeeklyReportSource.PENDING)
                .build();
    }

    // 종료 상태 전이(LLM / TEMPLATE_FALLBACK)는 엔티티 setter 가 아니라 WeeklyReportRepository 의 «PENDING 일 때만»
    // 조건부 UPDATE 로만 한다 — 읽고-바꾸기는 두 발행기 경합에서 나중 커밋이 먼저 것을 덮는다.
}
