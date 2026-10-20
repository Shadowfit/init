package com.shadowfit.dto.report;

/**
 * worst rep 계산에 필요한 컬럼만. joint_coordinates(2.3KB) 제외 → off-page I/O 회피.
 *
 * <p>{@code repNumber} 는 이슈 #78 로 추가됐다. 그전까지 이 프로젝션은 {@code sync_rate} 가
 * 프레임마다 다르다는 전제로 rep 을 싣지 않았는데, 실제 데이터는 <b>rep 안에서 상수</b>다
 * (ai-server 가 rep 단위로 채점해 프레임마다 복제 전송). rep 을 모르면 계산기가 rep 경계를
 * 넘는 것을 구조적으로 막을 수 없다.
 *
 * <p>{@code feedbackMessage} 는 <b>제거했다</b>(이슈 #80). 값이 문자열 3개뿐이고 전부
 * {@code sync_rate} 를 임계값과 비교한 결과라, 읽어봐야 싱크로율의 동어반복이었다. 유일한
 * 소비자였던 {@code pickDominantFeedback} 이 사라져 더는 조회할 이유가 없다.
 *
 * <p>{@code smoothedKneeAngle} 은 §4-ㄹ 로 추가됐다. {@code syncRate} 는 rep 안에서 상수라
 * <b>rep 을 고를 수는 있어도 rep 안의 프레임을 고를 수는 없다.</b> 대표 프레임을 "가장 깊었던
 * 순간"으로 정하려면 프레임마다 실제로 다른 값이 필요하고, 그게 이 컬럼이다.
 * {@code jointCoordinates} 를 싣지 않는 방침은 유지된다 — 각도는 숫자 하나라 off-page I/O 를
 * 되살리지 않으면서 프레임을 구분할 수 있다는 것이 이 안의 핵심이다.
 *
 * <p>{@code id} 는 P5 Tier 0(32-deferred-items.md)로 추가됐다. 대표 프레임이 정해진 뒤 그
 * 프레임의 {@code jointCoordinates} 하나만 <b>PK로 다시 조회</b>하기 위한 열쇠다 — 여기서
 * 값을 직접 싣지 않는 이유는 그대로다(대부분의 프레임은 대표로 안 뽑혀 그 비용이 버려진다).
 *
 * <p>{@code leadLeg} 는 V30(2026-09-27)로 추가됐다 — 런지에서 {@code smoothedKneeAngle} 이
 * "앞다리 무릎각"이 되면서(좌우 평균이 아니라), 그 값이 어느 다리의 것인지 없이는 좌/우로 나눠
 * 채점할 수 없다. {@code "left"}·{@code "right"} 둘 중 하나거나, 스쿼트·구버전 행이면 {@code null}
 * 이다.
 *
 * <p>{@code torsoTiltDeg} 는 V31(2026-09-28)로 추가됐다 — 어깨 중점-엉덩이 중점 벡터가 수직에서
 * 벗어난 각도(도), 스쿼트·런지 공용. {@link com.shadowfit.service.report.SquatTrunkLeanAnalysisCalculator}/
 * {@link com.shadowfit.service.report.LungeTrunkLeanAnalysisCalculator} 가 rep 내 평균으로
 * "그 회차 내내 체간 자세가 얼마나 무너졌는가"를 채점하는 근거값이다. 구버전 행이면
 * {@code null}(0과 구분 — 0은 "완전 직립"이라는 유효한 각도값이라 별도 미상 마커가 필요하다).
 *
 * <p>{@code staggerRatio} 는 V32(2026-09-28)로 추가됐다 — 두 발목 사이 거리 / 어깨-엉덩이 길이,
 * 런지 전용("보폭이 얼마나 벌어졌는가"). {@link com.shadowfit.service.report.LungeStepLengthAnalysisCalculator}
 * 가 짧은 보폭일수록 깊은 무릎 굴곡 구간에서 슬개대퇴 관절 부하가 커진다는 문헌 근거로 채점한다.
 * 스쿼트·구버전 행이면 {@code null}이다.
 */
public record PoseFrameProjection(
        Long id,
        Double timestampSec,
        Double syncRate,
        Integer repNumber,
        Double smoothedKneeAngle,
        String leadLeg,
        Double torsoTiltDeg,
        Double staggerRatio) {}
