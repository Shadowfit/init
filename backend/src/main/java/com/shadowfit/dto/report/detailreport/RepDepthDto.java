package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회차(rep) 하나의 깊이 정확도 — {@link RepSyncRateDto}의 깊이 버전 (2026-09-21).
 *
 * <p>{@code minKneeAngle}은 그 rep에서 가장 깊게 앉은 순간의 무릎각
 * ({@code SessionAnalysisCalculator.pickRepresentative}가 쓰는 것과 같은 선택 기준 —
 * 스쿼트 반복 판정 자체가 그 값으로 바닥을 확인하므로, "이 rep의 바닥"과 "채점 대상 프레임"이
 * 서로 다른 근거를 갖지 않는다).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepDepthDto {
    private int repNumber;
    private double minKneeAngle;
    private int depthScore;     // 0-100
    private String timeStamp;   // "01:15" — 가장 깊었던 프레임 기준
}
