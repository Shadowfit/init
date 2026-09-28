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
 * 런지 체간(몸통) 기울기 채점 + 회차별 한국어 코멘트 (2026-09-28).
 *
 * <p>{@link SquatTrunkLeanAnalysisCalculator}의 형제 클래스다 — 계산 방식(rep 평균 torsoTiltDeg)은
 * 같지만 임계값은 다르다. 스쿼트와 달리 런지의 전방 기울임은 <b>의도적인 정상 기법</b>일 수
 * 있다 — Farrokhi et al. 2008(<i>JOSPT</i> 38(7):403-409, Powers lab — 이미 인용한 슬개대퇴
 * 논문(JOSPT 2008;38(1))과 같은 연구 그룹)이 직립/전방기울임 조건을 비교했는데, 전방 기울임이
 * 둔근·햄스트링 활성을 높이는 정상적 변형이었다. 그래서 "직립=정답"으로 채점하지 않고, 오직
 * 초보자의 <b>보상성 과도한 전방 기울임</b>(코어 약화 → 무릎 과전방 이동 → 슬개건 스트레스
 * 증가)만 걸러내도록 스쿼트보다 훨씬 관대한 밴드를 쓴다.
 *
 * <p>🔴 <b>아래 35도/55도 경계는 이 경계를 특정하는 실측 문헌을 찾지 못해 잠정값이다.</b>
 * {@code SquatDepthAnalysisCalculator}의 HALF 모드 밴드와 같은 사정 — 실촬영 검증(다음
 * 하프 스쿼트 검증과 함께 진행 예정) 전까지 "런지 체간 자세 점수"를 사용자에게 확정적으로
 * 주장하지 않는다. 자세한 근거는 {@code docs/decisions/}에 검증 후 기록한다.
 */
@Component
public class LungeTrunkLeanAnalysisCalculator {

    // V2__seed_master_data.sql: 2=전방 런지. analyzer_registry.py의 _EXERCISE_ID_TO_TYPE와
    // 같은 결합 취약성을 그대로 안는다 — 시드가 바뀌면 이 값도 같이 고쳐야 한다.
    private static final long LUNGE_EXERCISE_ID = 2L;

    // VALIDATE — 스쿼트(20/35)보다 훨씬 관대하다. 의도적 전방 기울임이 정상 기법이라 명백한
    // 보상성 붕괴만 잡도록 경계를 높게 잡은 잠정값이다.
    private static final double NATURAL_LIMIT = 35.0;
    private static final double EXCESSIVE_THRESHOLD = 55.0;

    public TrunkLeanAnalysisDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        if (session.getExercise().getId() != LUNGE_EXERCISE_ID) {
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

    /** 35도 이하는 만점, 35~55도는 100→60 선형, 그 너머는 방어적으로 계속 깎는다. */
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
            if (a.getTiltScore() >= 85 && drop >= 15 && gotMoreBent && drop > biggestDrop) {
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
            sentences.add("상체가 과도하게 앞으로 쏠렸어요 — 코어에 힘을 주고 상체를 조금 더 세워보세요.");
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
