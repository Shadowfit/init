package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 체간(몸통) 기울기 정확도 리포트 (2026-09-28). 스쿼트·런지 공용 셰이프 — 좌우·모드 구분이
 * 필요한 깊이 DTO(SquatDepthAnalysisDto/LungeDepthAnalysisDto)와 달리 이 축은 두 운동이
 * 같은 계산(rep 평균 체간 기울기)을 쓰므로 공유해도 "다른 운동의 가정을 상속"하지 않는다.
 * 실제 채점 임계값은 {@link com.shadowfit.service.report.SquatTrunkLeanAnalysisCalculator}/
 * {@code LungeTrunkLeanAnalysisCalculator}가 운동별로 따로 정의한다.
 *
 * <p>스쿼트 세션이면 {@code SessionDetailedAnalysis.squatTrunkLeanAnalysis}에, 런지 세션이면
 * {@code lungeTrunkLeanAnalysis}에 실린다 — 두 필드 중 세션의 운동에 맞는 하나만 non-null이다
 * (squatDepthAnalysis/lungeDepthAnalysis와 같은 null 가드 규약).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TrunkLeanAnalysisDto {
    private int tiltScore;      // 세션 평균, 0-100
    private String tiltGrade;   // EXCELLENT/GOOD/FAIR/NEEDS_WORK/POOR — 깊이 등급과 같은 5단계
    private List<RepTrunkLeanDto> repTrunkLeans;
    private List<String> narrative; // 회차별 한국어 코멘트, 최대 4문장
}
