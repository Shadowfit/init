package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameGrouping;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepTrunkLeanDto;
import com.shadowfit.dto.report.detailreport.TrunkLeanAnalysisDto;
import com.shadowfit.model.exercise.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 스쿼트 체간(몸통) 기울기 채점 + 회차별 한국어 코멘트 (2026-09-28).
 *
 * <p>{@link SquatDepthAnalysisCalculator}의 형제 클래스다 — 깊이(얼마나 앉았는가)와는 별개
 * 축이라 따로 뒀다. rep 그룹핑만 {@link PoseFrameGrouping#byRep}로 공유한다.
 *
 * <p>이 rep의 점수는 프레임별 {@code torsoTiltDeg}의 <b>평균</b>이다 — 깊이 점수가 최솟값 프레임
 * (정점 하나)을 쓰는 것과 다른 기준인 이유는 {@link RepTrunkLeanDto} 참고. 기존 실시간 BACK_BENT
 * 판정(ai-server app/api/endpoints/pose.py)도 이미 이 방식(rep 전체 평균)을 쓰고 있어 그대로
 * 계승했다 — 같은 35도가 실시간 피드백과 세션 후 점수 양쪽에서 "감점 시작 경계"로 일관된다.
 *
 * <p>근거: FMS(Functional Movement Screen) Deep Squat 만점 조건에 "torso stays upright"·
 * "tibia and torso parallel"이 명시돼 있고, 몸통-정강이 각도 40도는 "다소 과함", 45도를 넘으면
 * 무게중심이 지지 기반을 벗어나는 명백한 과도 전방 기울임으로 문헌에 정리돼 있다(Straub,
 * <i>A Biomechanical Review of the Squat Exercise</i>, IJSPT). 40도 전후 전방 기울임이 ACL
 * 부담을 16~24% 낮추지만 햄스트링 부하를 35% 늘리는 트레이드오프도 있어 "직립일수록 무조건
 * 좋다"로 설계하지 않았다.
 *
 * <p>🔄 2026-10-05 실측 보정: 처음엔 20도까지 만점으로 잡았는데, 실촬영 정상 스쿼트 3개
 * (squat_normal_01·02·side_01)의 rep 중 체간 기울기 평균이 29.8·31.1·31.3도로 나와 멀쩡한
 * 스쿼트가 FAIR("상체가 많이 숙여졌어요")로 깎였다. 그래서 만점 경계를 기존 실시간 BACK_BENT
 * 경계인 35도로 올리고, 문헌상 "명백한 과도"인 45도에서 60점이 되게 했다.
 */
@Component
public class SquatTrunkLeanAnalysisCalculator {

    // V2__seed_master_data.sql: 1=스쿼트. analyzer_registry.py의 _EXERCISE_ID_TO_TYPE와 같은
    // 결합 취약성을 그대로 안는다 — 시드가 바뀌면 이 값도 같이 고쳐야 한다.
    private static final long SQUAT_EXERCISE_ID = 1L;

    // ai-server pose.py의 _BACK_BENT_TILT_THRESHOLD와 같은 값 — 실시간 피드백과 세션 후 점수가
    // 같은 경계를 말하도록 의도적으로 맞췄다. 값이 어긋나면 두 경로가 "등 굽음"을 다른 기준으로
    // 판정하게 되니 둘 중 하나만 바꾸지 말 것.
    private static final double NATURAL_LIMIT = 35.0;
    // 문헌(Straub, IJSPT)이 "명백한 과도 전방 기울임"으로 꼽는 경계.
    private static final double EXCESSIVE_THRESHOLD = 45.0;

    public TrunkLeanAnalysisDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        if (session.getExercise().getId() != SQUAT_EXERCISE_ID) {
            return null;
        }
        if (poseFrames == null || poseFrames.isEmpty()) {
            return null;
        }

        List<RepTrunkLeanDto> repTrunkLeans = new ArrayList<>();
        for (Map.Entry<Integer, List<PoseFrameProjection>> entry : PoseFrameGrouping.byRep(poseFrames).entrySet()) {
            RepTrunkLeanDto rep = scoreRep(entry.getKey(), entry.getValue());
            if (rep != null) {
                repTrunkLeans.add(rep);
            }
        }
        if (repTrunkLeans.isEmpty()) {
            return null; // 유효한 체간 기울기 데이터가 있는 rep이 하나도 없음(구버전 행뿐이거나 전부 미계산)
        }

        int sessionScore = (int) Math.round(
                repTrunkLeans.stream().mapToInt(RepTrunkLeanDto::getTiltScore).average().orElse(0.0));

        TrunkLeanAnalysisDto dto = new TrunkLeanAnalysisDto();
        dto.setTiltScore(sessionScore);
        dto.setTiltGrade(gradeOf(sessionScore));
        dto.setRepTrunkLeans(repTrunkLeans);
        dto.setNarrative(buildNarrative(repTrunkLeans, sessionScore));
        return dto;
    }

    /** 이 rep에 속한 프레임들의 torsoTiltDeg 평균을 채점한다. 유효값이 없으면 null. */
    private RepTrunkLeanDto scoreRep(int repNumber, List<PoseFrameProjection> frames) {
        List<Double> tilts = frames.stream()
                .map(PoseFrameProjection::torsoTiltDeg)
                .filter(Objects::nonNull)
                .toList();
        if (tilts.isEmpty()) {
            return null;
        }
        double avgTilt = tilts.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        int score = scoreTilt(avgTilt);
        return new RepTrunkLeanDto(repNumber, avgTilt, score, formatTimestamp(frames.get(0).timestampSec()));
    }

    /** 35도 이하는 만점, 35~45도는 100→60 선형, 그 너머는 방어적으로 계속 깎는다. */
    private int scoreTilt(double avgTilt) {
        if (avgTilt <= NATURAL_LIMIT) {
            return 100;
        }
        if (avgTilt <= EXCESSIVE_THRESHOLD) {
            return (int) Math.round(100 - (avgTilt - NATURAL_LIMIT) / (EXCESSIVE_THRESHOLD - NATURAL_LIMIT) * 40);
        }
        return (int) Math.max(0, Math.round(60 - (avgTilt - EXCESSIVE_THRESHOLD) * 3));
    }

    private String gradeOf(int score) {
        if (score >= 90) return "EXCELLENT";
        if (score >= 75) return "GOOD";
        if (score >= 60) return "FAIR";
        if (score >= 40) return "NEEDS_WORK";
        return "POOR";
    }

    /**
     * 회차별 한국어 코멘트, 최대 4문장.
     *
     * <p>우선순위: ① 인접 회차 대비(앞 회차 ≥85점, 바로 다음 회차가 15점 이상 낮고 실제로 더
     * 기울어짐) &gt; ② 세션 전체 등급이 낮음(FAIR 미만) &gt; ③ 전부 양호(폴백).
     */
    private List<String> buildNarrative(List<RepTrunkLeanDto> repTrunkLeans, int sessionScore) {
        List<String> sentences = new ArrayList<>();

        RepTrunkLeanDto dropFrom = null, dropTo = null;
        int biggestDrop = 0;
        for (int i = 0; i + 1 < repTrunkLeans.size(); i++) {
            RepTrunkLeanDto a = repTrunkLeans.get(i), b = repTrunkLeans.get(i + 1);
            int drop = a.getTiltScore() - b.getTiltScore();
            boolean gotMoreBent = b.getAvgTiltDeg() > a.getAvgTiltDeg();
            // b가 75점 미만일 때만 경고한다 — 낙차만 보면 93점대 세션에서도 "많이 숙여졌어요"가
            // 떠서 점수(EXCELLENT)와 코멘트가 모순된다(2026-10-05 실측 e2e에서 확인).
            if (a.getTiltScore() >= 85 && b.getTiltScore() < 75 && drop >= 15 && gotMoreBent && drop > biggestDrop) {
                biggestDrop = drop;
                dropFrom = a;
                dropTo = b;
            }
        }
        if (dropFrom != null) {
            sentences.add(String.format(
                    "%d회차까지는 상체를 잘 세웠는데, %d회차부터 상체가 많이 숙여졌어요.",
                    dropFrom.getRepNumber(), dropTo.getRepNumber()));
        }

        if (sentences.size() < 4 && sessionScore < 60) {
            sentences.add("전반적으로 상체가 많이 숙여졌어요 — 시선을 살짝 위로 두고 가슴을 펴보세요.");
        }

        if (sentences.isEmpty()) {
            sentences.add(String.format("%d회 모두 상체 자세를 잘 유지했어요.", repTrunkLeans.size()));
        }

        return sentences.size() > 4 ? sentences.subList(0, 4) : sentences;
    }

    private String formatTimestamp(Double timestampSec) {
        if (timestampSec == null) return "00:00";
        int totalSeconds = timestampSec.intValue();
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
