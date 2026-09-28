package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 스쿼트 깊이 정확도 리포트 (2026-09-21) — {@code SquatDepthAnalysisCalculator}가 만들고
 * {@link SessionDetailedAnalysis}에 실려 precompute-on-write로 저장된다.
 *
 * <p>스쿼트 세션(exercise_id=1)이면서 유효한 {@code smoothedKneeAngle} 데이터가 있을 때만
 * 만들어진다 — 런지 세션이나 구버전 데이터는 이 필드 자체가 null이다
 * ({@link com.shadowfit.dto.report.detailreport.SessionReportResponseDto}의 {@code scoreAvailable}과
 * 같은 널 가드 규약).
 *
 * <p>깊이 임계값(115/140도)은 실촬영 하프 스쿼트 영상으로 검증되기 전까지 잠정값이다 —
 * {@code docs/decisions/squat-repetition-counting.md}의 2026-09-21 절 참고.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SquatDepthAnalysisDto {
    private String depthMode;        // "FULL" | "HALF" — 세션 완료 시점의 squatDepthMode
    private int depthScore;          // 회차별 depthScore의 평균, 0-100
    private String depthGrade;       // "EXCELLENT" | "GOOD" | "FAIR" | "NEEDS_WORK" | "POOR"
    private List<RepDepthDto> repDepths;
    private List<String> narrative;  // 회차별 한국어 코멘트, 최대 4문장
}
