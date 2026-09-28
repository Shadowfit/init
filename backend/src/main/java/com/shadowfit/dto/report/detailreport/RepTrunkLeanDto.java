package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회차(rep) 하나의 체간 자세 정확도 — {@link RepDepthDto}의 체간 기울기 버전 (2026-09-28).
 *
 * <p>{@code avgTiltDeg}는 이 rep에 속한 프레임들의 {@code torsoTiltDeg} <b>평균</b>이다.
 * 깊이 점수가 "최솟값 프레임"(가장 깊었던 한 순간)을 쓰는 것과 다른 기준이다 — 깊이는 "얼마나
 * 갔는가"(정점 하나가 의미 있음)이고 체간 자세는 "그 rep 내내 얼마나 무너졌는가"(평균이
 * 의미 있음)이기 때문이다. 기존 실시간 BACK_BENT 판정(pose.py)도 이미 rep 전체 평균을 쓴다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepTrunkLeanDto {
    private int repNumber;
    private double avgTiltDeg;
    private int tiltScore;      // 0-100
    private String timeStamp;   // "01:15" — 그 rep의 첫 프레임 기준
}
