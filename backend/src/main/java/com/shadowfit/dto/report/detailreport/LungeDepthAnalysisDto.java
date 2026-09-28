package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 런지 깊이 정확도 리포트 (2026-09-27) — {@code LungeDepthAnalysisCalculator}가 만들고
 * {@link SessionDetailedAnalysis}에 실려 precompute-on-write로 저장된다.
 *
 * <p>런지 세션(exercise_id=2)이면서 유효한 좌우 구분 깊이 데이터가 있을 때만 만들어진다 —
 * 스쿼트 세션·구버전 데이터·좌우 정보가 없는 세션(V24 이전에 완료된 런지 등)은 이 필드
 * 자체가 null이다({@link SessionReportResponseDto}의 {@code scoreAvailable}과 같은 널 가드 규약).
 *
 * <p>목표선(100도)의 근거는 {@code docs/decisions/lunge-repetition-counting.md} §2·§3 —
 * Sousa et al.(2026)이 런지 "진입" 단계를 양 무릎 100도 미만으로 정의한 것과 같은 값이다.
 * 채점 자체는 앞다리(lead) 무릎각 하나만 본다 — 뒷다리는 판정에 들어가지 않는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LungeDepthAnalysisDto {
    private int depthScore;            // 회차별 depthScore의 평균, 0-100
    private String depthGrade;         // "EXCELLENT" | "GOOD" | "FAIR" | "NEEDS_WORK" | "POOR"
    private Double leftAvgKneeAngle;   // 왼쪽 회차 평균 최저 무릎각 — 왼쪽 회차가 없으면 null
    private Double rightAvgKneeAngle;  // 오른쪽 회차 평균 최저 무릎각 — 오른쪽 회차가 없으면 null
    private List<LungeRepDepthDto> repDepths;
    private List<String> narrative;    // 회차별 한국어 코멘트, 최대 4문장
}
