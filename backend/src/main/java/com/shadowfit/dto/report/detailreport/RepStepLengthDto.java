package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회차(rep) 하나의 보폭 정확도 — 런지 전용(2026-09-28). {@code staggerRatio}는 두 발목 사이
 * 거리 / 어깨-엉덩이 길이다. 이 앱의 런지는 발 위치가 고정된 채 굽혔다 펴는 방식이라(제자리
 * 펄싱, docs/decisions/lunge-repetition-counting.md 참고) rep 안에서 값이 거의 변하지
 * 않는다 — 깊이·체간 축처럼 최솟값/평균을 따로 집계하지 않고 대표 프레임 값을 그대로 쓴다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepStepLengthDto {
    private int repNumber;
    private double staggerRatio;
    private int stepScore;      // 0-100
    private String timeStamp;   // "01:15"
}
