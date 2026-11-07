package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회차(rep) 하나의 깊이 정확도 — {@link RepDepthDto}의 런지 버전 (2026-09-27).
 *
 * <p>{@link RepDepthDto}를 그대로 재사용하지 않은 이유: 런지는 좌/우 어느 다리인지가
 * 채점만큼 중요한 정보라({@code side}), 스쿼트 전용 DTO에 필드를 얹어 결합시키는 대신
 * 형제 DTO로 분리했다 — {@link LungeDepthAnalysisCalculator}가
 * {@link com.shadowfit.service.report.SquatDepthAnalysisCalculator}의 형제 클래스로 분리된 것과
 * 같은 이유(각 종목의 계산이 서로의 가정을 안 타게 한다).
 *
 * <p>{@code minKneeAngle}은 그 rep에서 앞다리가 가장 깊게 굽혀진 순간의 무릎각이다 — 런지
 * 반복 판정 자체가 그 값(lead 다리 무릎각)으로 바닥을 확인하므로, "이 rep의 바닥"과 "채점
 * 대상 프레임"이 서로 다른 근거를 갖지 않는다(ai-server app/core/lunge_analyzer.py).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LungeRepDepthDto {
    private int repNumber;
    private String side;        // "left" | "right"
    private double minKneeAngle;
    private int depthScore;     // 0-100
    private String timeStamp;   // "01:15" — 가장 깊었던 프레임 기준
}
