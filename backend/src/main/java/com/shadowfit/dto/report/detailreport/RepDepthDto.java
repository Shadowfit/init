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
 *
 * <p><b>«미인정 시도» 도 한 행이 된다 (2026-10-21, V35)</b> — {@code counted=false}·{@code repNumber=null}·
 * {@code attemptNumber=k}. 채점 공식은 인정 반복과 같고(같은 목표·같은 감점), 세션 {@code depthScore} 는 모든 행의 평균이다.
 * {@code minKneeAngle} 은 AI 가 보낸 «그 시도에서 가장 깊이 앉은 순간» 의 무릎각이다. 이 필드들이 생기기 전에 저장된
 * 리포트는 {@code counted} 가 기본값 {@code true}, {@code attemptNumber} 가 null 로 읽힌다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepDepthDto {
    private Integer repNumber;      // 인정 반복의 회차. 미인정 시도는 null
    private double minKneeAngle;
    private int depthScore;         // 0-100
    private String timeStamp;       // "01:15" — 가장 깊었던 프레임 기준
    private boolean counted = true; // false = 미인정 시도
    private Integer attemptNumber;  // 미인정 시도 번호(1부터). 인정 반복은 null

    /** 인정 반복 한 행. */
    public RepDepthDto(Integer repNumber, double minKneeAngle, int depthScore, String timeStamp) {
        this(repNumber, minKneeAngle, depthScore, timeStamp, true, null);
    }

    /** 미인정 시도 한 행. */
    public static RepDepthDto rejected(int attemptNumber, double minKneeAngle, int depthScore, String timeStamp) {
        return new RepDepthDto(null, minKneeAngle, depthScore, timeStamp, false, attemptNumber);
    }
}
