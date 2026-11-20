package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 런지 보폭 정확도 리포트 (2026-09-28). 런지 전용 — 스쿼트는 보폭 개념이 없어 대응 필드가
 * 없다(깊이·체간 기울기와 달리 스쿼트 형제 계산기가 존재하지 않는다).
 *
 * <p>🔴 <b>아래 임계값(1.0/0.6)은 실촬영 4개 중 검증된 3개(정상·얕음·워킹)에서 관찰한 stagger
 * 분포와 문헌(Escamilla et al. 계열의 짧은 보폭-슬개대퇴 부하 연구)을 근거로 잡은 잠정값이다.</b>
 * "얕음"으로 이미 확인된 영상이 "정상" 영상보다 stagger도 독립적으로 낮게 나와(중앙값 0.88 vs
 * 1.12) 우연이 아닌 실제 상관관계로 보이지만, 표본이 3개뿐이라 확정 임계값은 아니다 — 스쿼트
 * 하프 모드·런지 체간 기울기와 같은 검증 절차(실촬영 확보 후 재검토)를 거쳐야 한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LungeStepLengthAnalysisDto {
    private int stepScore;      // 세션 평균, 0-100
    private String stepGrade;   // EXCELLENT/GOOD/FAIR/NEEDS_WORK/POOR
    private List<RepStepLengthDto> repStepLengths;
    private List<String> narrative; // 회차별 한국어 코멘트, 최대 4문장
}
