package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameGrouping;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.LungeStepLengthAnalysisDto;
import com.shadowfit.dto.report.detailreport.RepStepLengthDto;
import com.shadowfit.model.exercise.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 런지 보폭 정확도 채점 + 회차별 한국어 코멘트 (2026-09-28). 런지 전용 — 스쿼트는 보폭
 * 개념이 없어(양발이 나란함) 형제 계산기가 없다.
 *
 * <p>근거: Escamilla et al. 계열 연구(이미 인용된 슬개대퇴 관절 부하 연구와 같은 계보)가
 * 짧은 보폭 런지일수록 깊은 무릎 굴곡 구간(50~100도)에서 슬개대퇴 관절 부하가 유의하게
 * 커진다고 반복 보고한다 — "무릎이 발끝을 넘는가" 자체(최근 재활 문헌에서 근거 없는 통념으로
 * 반박됨)가 아니라 <b>보폭 대비 깊이</b>가 핵심 메커니즘이다.
 *
 * <p>이 rep의 대표값은 {@link LungeDepthAnalysisCalculator}와 같은 프레임(그 rep에서 무릎이
 * 가장 깊게 굽혀진 순간)의 {@code staggerRatio}를 쓴다 — 이 앱의 런지는 발 위치가 고정된 채
 * 굽혔다 펴는 방식이라 rep 안에서 stagger가 거의 변하지 않으므로, 새 대표 프레임 규칙을
 * 만들지 않고 이미 검증된 "가장 깊었던 순간" 선택을 재사용한다.
 *
 * <p>🔴 임계값 근거와 미검증 범위는 {@link LungeStepLengthAnalysisDto} 참고.
 */
@Component
public class LungeStepLengthAnalysisCalculator {

    // V2__seed_master_data.sql: 2=전방 런지.
    private static final long LUNGE_EXERCISE_ID = 2L;

    // VALIDATE — 실촬영 3개(정상·얕음·워킹) 관찰 + 문헌 근거의 잠정값. 상세 근거는
    // LungeStepLengthAnalysisDto 참고.
    private static final double ADEQUATE_STEP = 1.0;
    private static final double SHORT_ENTRY = 0.6;

    public LungeStepLengthAnalysisDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        if (session.getExercise().getId() != LUNGE_EXERCISE_ID) {
            return null;
        }
        if (poseFrames == null || poseFrames.isEmpty()) {
            return null;
        }

        List<RepStepLengthDto> repStepLengths = new ArrayList<>();
        for (Map.Entry<Integer, List<PoseFrameProjection>> entry : PoseFrameGrouping.byRep(poseFrames).entrySet()) {
            RepStepLengthDto rep = scoreRep(entry.getKey(), entry.getValue());
            if (rep != null) {
                repStepLengths.add(rep);
            }
        }
        if (repStepLengths.isEmpty()) {
            return null; // 유효한 보폭 데이터가 있는 rep이 하나도 없음(구버전 행뿐이거나 전부 미계산)
        }

        int sessionScore = (int) Math.round(
                repStepLengths.stream().mapToInt(RepStepLengthDto::getStepScore).average().orElse(0.0));

        LungeStepLengthAnalysisDto dto = new LungeStepLengthAnalysisDto();
        dto.setStepScore(sessionScore);
        dto.setStepGrade(gradeOf(sessionScore));
        dto.setRepStepLengths(repStepLengths);
        dto.setNarrative(buildNarrative(repStepLengths, sessionScore));
        return dto;
    }

    /**
     * 이 rep에서 무릎이 가장 깊게 굽혀진 순간(최소 smoothedKneeAngle)의 staggerRatio를 채점한다.
     * 그 프레임의 staggerRatio가 없으면(구버전 행) null.
     */
    private RepStepLengthDto scoreRep(int repNumber, List<PoseFrameProjection> frames) {
        PoseFrameProjection deepest = null;
        for (PoseFrameProjection frame : frames) {
            Double kneeAngle = frame.smoothedKneeAngle();
            if (kneeAngle == null || kneeAngle <= 0.0) {
                continue;
            }
            if (deepest == null || kneeAngle < deepest.smoothedKneeAngle()) {
                deepest = frame;
            }
        }
        if (deepest == null || deepest.staggerRatio() == null) {
            return null;
        }
        double stagger = deepest.staggerRatio();
        return new RepStepLengthDto(repNumber, stagger, scoreStep(stagger), formatTimestamp(deepest.timestampSec()));
    }

    /** 1.0 이상은 만점, 0.6~1.0은 100→60 선형, 그 너머는 방어적으로 계속 깎는다. */
    private int scoreStep(double stagger) {
        if (stagger >= ADEQUATE_STEP) {
            return 100;
        }
        if (stagger >= SHORT_ENTRY) {
            return (int) Math.round(100 - (ADEQUATE_STEP - stagger) / (ADEQUATE_STEP - SHORT_ENTRY) * 40);
        }
        return (int) Math.max(0, Math.round(60 - (SHORT_ENTRY - stagger) * 100));
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
     * 짧아짐) &gt; ② 세션 전체 등급이 낮음(FAIR 미만) &gt; ③ 전부 양호(폴백).
     */
    private List<String> buildNarrative(List<RepStepLengthDto> repStepLengths, int sessionScore) {
        List<String> sentences = new ArrayList<>();

        RepStepLengthDto dropFrom = null, dropTo = null;
        int biggestDrop = 0;
        for (int i = 0; i + 1 < repStepLengths.size(); i++) {
            RepStepLengthDto a = repStepLengths.get(i), b = repStepLengths.get(i + 1);
            int drop = a.getStepScore() - b.getStepScore();
            boolean gotShorter = b.getStaggerRatio() < a.getStaggerRatio();
            // b가 75점 미만일 때만 경고한다 — 점수와 코멘트가 모순되지 않도록.
            if (a.getStepScore() >= 85 && b.getStepScore() < 75 && drop >= 15 && gotShorter && drop > biggestDrop) {
                biggestDrop = drop;
                dropFrom = a;
                dropTo = b;
            }
        }
        if (dropFrom != null) {
            sentences.add(String.format(
                    "%d회차까지는 보폭이 넉넉했는데, %d회차부터 보폭이 짧아져 무릎에 부담이 갈 수 있어요.",
                    dropFrom.getRepNumber(), dropTo.getRepNumber()));
        }

        if (sentences.size() < 4 && sessionScore < 60) {
            sentences.add("전반적으로 보폭이 짧았어요 — 앞발을 조금 더 앞으로 내디뎌보세요.");
        }

        if (sentences.isEmpty()) {
            sentences.add(String.format("%d회 모두 보폭이 안정적이었어요.", repStepLengths.size()));
        }

        return sentences.size() > 4 ? sentences.subList(0, 4) : sentences;
    }

    private String formatTimestamp(Double timestampSec) {
        if (timestampSec == null) return "00:00";
        int totalSeconds = timestampSec.intValue();
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
