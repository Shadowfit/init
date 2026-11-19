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
 *
 * <p><b>{@code targetKneeDeg}(2026-10-21)</b>: FULL 모드 채점의 목표 무릎각 = 기준(시범) 최저 무릎각 + 허용 오차.
 * 앱이 «목표: 무릎 76° 이하» 로 보여 준다. 저장(precompute) 시점의 값이 박제된다 — 나중에 기준 영상이 바뀌어도
 * 지난 리포트의 목표·점수는 그대로다(그 세션의 싱크로율도 당시 기준으로 매겨졌으므로). HALF 모드와
 * 이 필드가 생기기 전에 저장된 리포트(옛 «100도 이하 만점» 규칙으로 채점됨)는 null 이다.
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
    private Double targetKneeDeg;    // FULL 목표 무릎각(이하면 만점), 소수 첫째 자리. HALF·옛 리포트는 null
}
