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
 *
 * <p><b>«미인정 시도» 도 한 행이 된다 (2026-10-21, V35, 스쿼트만)</b> — {@code counted=false}·{@code repNumber=null}·
 * {@code attemptNumber=k}. AI 가 시도를 «가장 깊이 앉은 순간» 한 장으로 요약해 보내므로 이 행의 {@code avgTiltDeg} 는
 * 평균이 아니라 <b>그 순간 한 장의 값</b>이다(점수 공식은 같다). 옛 리포트는 {@code counted} 가 기본값 {@code true} 로 읽힌다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepTrunkLeanDto {
    private Integer repNumber;      // 인정 반복의 회차. 미인정 시도는 null
    private double avgTiltDeg;
    private int tiltScore;          // 0-100
    private String timeStamp;       // "01:15" — 인정 반복은 그 rep의 첫 프레임, 미인정 시도는 가장 깊이 앉은 순간
    private boolean counted = true; // false = 미인정 시도
    private Integer attemptNumber;  // 미인정 시도 번호(1부터). 인정 반복은 null

    /** 인정 반복 한 행. */
    public RepTrunkLeanDto(Integer repNumber, double avgTiltDeg, int tiltScore, String timeStamp) {
        this(repNumber, avgTiltDeg, tiltScore, timeStamp, true, null);
    }

    /** 미인정 시도 한 행. */
    public static RepTrunkLeanDto rejected(int attemptNumber, double tiltDeg, int tiltScore, String timeStamp) {
        return new RepTrunkLeanDto(null, tiltDeg, tiltScore, timeStamp, false, attemptNumber);
    }
}
