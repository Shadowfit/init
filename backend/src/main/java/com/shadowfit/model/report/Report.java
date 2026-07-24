package com.shadowfit.model.report;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Id;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
public class Report extends BaseTimeEntity{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // 실 schema.sql의 ON DELETE CASCADE와 일치
    private Member member;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // 실 schema.sql의 ON DELETE CASCADE와 일치 — 세션 삭제 시 함께 정리
    private Session session;

    @Enumerated(EnumType.STRING)
    private ReportType reportType; // SESSION, WEEKLY, MONTHLY

    @Column(columnDefinition = "TEXT")
    private String summary; // GPT가 요약한 전체 총평

    @Column(columnDefinition = "TEXT")
    private String improvementTips; // 개선 포인트 (불렛 포인트 등)

    /**
     * 상세 분석 데이터 (JSON)
     * Worst 구간, 파트별 점수 등 복잡한 구조를 저장
     */
    @Column(columnDefinition = "json")
    private String detailedAnalysis;

    /**
     * 이전 기록과의 비교 데이터 (JSON)
     * 예: { "syncRateChange": +5.2, "repChange": -2 }
     */
    @Column(columnDefinition = "json")
    private String comparisonWithPrevious;
}
