package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameGrouping;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.LungeDepthAnalysisDto;
import com.shadowfit.dto.report.detailreport.LungeRepDepthDto;
import com.shadowfit.model.exercise.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 런지 깊이 정확도 채점 + 회차별 한국어 코멘트 (2026-09-27).
 *
 * <p>{@link SquatDepthAnalysisCalculator}의 형제 클래스다 — 같은 이유로 분리했다: rep 그룹핑
 * 규칙만 {@link PoseFrameGrouping#byRep}로 공유하고, 채점·코멘트 로직은 종목마다 독립적으로
 * 둔다. 스쿼트와 다른 점 두 가지가 이 분리를 요구한다 — ① FULL/HALF 모드 구분이 없다(런지는
 * 깊이 모드 선택 자체가 없음), ② 좌/우 어느 다리인지가 채점 결과의 일부다(스쿼트는 좌우
 * 대칭이라 이 축이 없다).
 *
 * <p>목표선(100도)의 근거는 {@code docs/decisions/lunge-repetition-counting.md} 참고 — 이
 * 저장소가 이미 인용하는 Sousa et al.(2026)이 런지 "진입" 단계를 양 무릎 100도 미만으로
 * 정의한 것과 같은 값이며, ai-server의 {@code LungeThresholds.bottom_angle}과도 같다. 채점은
 * 스쿼트 FULL 모드와 같은 모양(100도 이하 만점, 100~115도 선형, 그 너머 방어적 감점)을 쓴다 —
 * 별도 문헌(슬개대퇴 관절 부하 연구, JOSPT 2008;38(1))이 "더 깊이 굽힐수록, 특히 보폭이
 * 짧을수록 무릎 부담이 커진다"고 보고해 "더 깊을수록 무조건 좋다"로 설계하지 않았다.
 */
@Component
public class LungeDepthAnalysisCalculator {

    // V2__seed_master_data.sql: 2=전방 런지. analyzer_registry.py의 _EXERCISE_ID_TO_TYPE와
    // 같은 결합 취약성을 그대로 안는다 — 시드가 바뀌면 이 값도 같이 고쳐야 한다.
    private static final long LUNGE_EXERCISE_ID = 2L;

    private static final double TARGET = 100.0;
    private static final double ENTRY = 115.0;   // ai-server bottom_angle(100) + margin(15, 스쿼트와 동일 여유)

    // 회차 평균이 이보다 깊지 않으면(목표선에 가까우면) 통과해도 "아슬아슬" 코멘트를 남긴다.
    // 2026-09-22 실촬영 lunge_sallow_01(좌89도·우98도 평균)이 정확히 이 경우였다 — 전부
    // 100점으로 통과했지만 목표선에 바짝 붙어 있었다.
    private static final double CLOSE_CALL_KNEE = 85.0;

    // 좌우 평균 최저 무릎각 차이가 이보다 크면 비대칭으로 본다. 2026-09-22 실촬영 3개(정상·얕음·
    // 워킹)는 전부 6.5~8.7도라 이 문턱을 넘지 않았다 — 실측 근거는 "이 사람은 균형이 맞았다"는
    // 확인이지 "10도가 맞는 경계"라는 검증은 아니다. 구현 기본값이다.
    private static final double ASYMMETRY_THRESHOLD = 10.0;

    public LungeDepthAnalysisDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        if (session.getExercise().getId() != LUNGE_EXERCISE_ID) {
            return null;
        }
        if (poseFrames == null || poseFrames.isEmpty()) {
            return null;
        }

        List<LungeRepDepthDto> repDepths = new ArrayList<>();
        for (Map.Entry<Integer, List<PoseFrameProjection>> entry : PoseFrameGrouping.byRep(poseFrames).entrySet()) {
            LungeRepDepthDto repDepth = scoreRep(entry.getKey(), entry.getValue());
            if (repDepth != null) {
                repDepths.add(repDepth);
            }
        }
        if (repDepths.isEmpty()) {
            return null; // 유효한 무릎각 데이터가 있는 rep이 하나도 없음
        }

        int sessionScore = (int) Math.round(
                repDepths.stream().mapToInt(LungeRepDepthDto::getDepthScore).average().orElse(0.0));

        Double leftAvg = averageKneeAngle(repDepths, "left");
        Double rightAvg = averageKneeAngle(repDepths, "right");

        LungeDepthAnalysisDto dto = new LungeDepthAnalysisDto();
        dto.setDepthScore(sessionScore);
        dto.setDepthGrade(gradeOf(sessionScore));
        dto.setLeftAvgKneeAngle(leftAvg);
        dto.setRightAvgKneeAngle(rightAvg);
        dto.setRepDepths(repDepths);
        dto.setNarrative(buildNarrative(repDepths, leftAvg, rightAvg));
        return dto;
    }

    /** 이 rep에서 앞다리가 가장 깊게 굽혀진 순간(최소 smoothedKneeAngle)을 채점한다. 유효값이 없으면 null. */
    private LungeRepDepthDto scoreRep(int repNumber, List<PoseFrameProjection> frames) {
        PoseFrameProjection deepest = null;
        String side = null;
        for (PoseFrameProjection frame : frames) {
            if (side == null && frame.leadLeg() != null) {
                side = frame.leadLeg();
            }
            Double kneeAngle = frame.smoothedKneeAngle();
            if (kneeAngle == null || kneeAngle <= 0.0) {
                continue;
            }
            if (deepest == null || kneeAngle < deepest.smoothedKneeAngle()) {
                deepest = frame;
            }
        }
        if (deepest == null) {
            return null;
        }
        double minKnee = deepest.smoothedKneeAngle();
        return new LungeRepDepthDto(repNumber, side, minKnee, scoreFull(minKnee), formatTimestamp(deepest.timestampSec()));
    }

    /** 100도 이하는 만점, 100~115도는 100→60 선형, 그 너머는 방어적으로 계속 깎는다(스쿼트 FULL과 같은 모양). */
    private int scoreFull(double minKnee) {
        if (minKnee <= TARGET) {
            return 100;
        }
        if (minKnee <= ENTRY) {
            return (int) Math.round(100 - (minKnee - TARGET) / (ENTRY - TARGET) * 40);
        }
        return (int) Math.max(0, Math.round(60 - (minKnee - ENTRY) * 2));
    }

    private String gradeOf(int score) {
        if (score >= 90) return "EXCELLENT";
        if (score >= 75) return "GOOD";
        if (score >= 60) return "FAIR";
        if (score >= 40) return "NEEDS_WORK";
        return "POOR";
    }

    private Double averageKneeAngle(List<LungeRepDepthDto> repDepths, String side) {
        List<Double> matching = repDepths.stream()
                .filter(rep -> side.equals(rep.getSide()))
                .map(LungeRepDepthDto::getMinKneeAngle)
                .toList();
        if (matching.isEmpty()) {
            return null;
        }
        return matching.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 회차별 한국어 코멘트, 최대 4문장. 리포트를 짧게 유지하려고 트리거된 것만 우선순위 순으로 채운다.
     *
     * <p>우선순위: ① 좌우 비대칭(스쿼트에는 없는 런지 전용 축) &gt; ② 같은 다리 안에서 인접 회차
     * 대비(앞 회차 ≥85점, 바로 다음 같은 다리 회차가 15점 이상 낮음) &gt; ③ 통과는 했지만 목표선에
     * 아슬아슬(세션 평균 무릎각이 얕은 쪽) &gt; ④ 회차 간 편차가 큼(stddev &gt; 10도) &gt;
     * ⑤ 전부 양호(다른 트리거가 없을 때만, 폴백).
     */
    private List<String> buildNarrative(List<LungeRepDepthDto> repDepths, Double leftAvg, Double rightAvg) {
        List<String> sentences = new ArrayList<>();

        // ① 좌우 비대칭 — 양쪽 다 데이터가 있을 때만 비교할 수 있다.
        if (leftAvg != null && rightAvg != null && Math.abs(leftAvg - rightAvg) >= ASYMMETRY_THRESHOLD) {
            String shallowerSide = leftAvg > rightAvg ? "왼쪽" : "오른쪽";
            sentences.add(String.format(
                    "%s 다리가 반대쪽보다 덜 앉았어요(왼쪽 평균 %.0f도, 오른쪽 평균 %.0f도) — 균형을 맞춰보세요.",
                    shallowerSide, leftAvg, rightAvg));
        }

        // ② 같은 다리 안에서 인접 회차 대비 — 가장 큰 낙차 한 쌍만. 다른 다리로 넘어가는 지점은
        // 비교하지 않는다(워킹 런지처럼 좌우 번갈아 진행하면 서로 다른 다리라 비교 자체가 의미 없다).
        if (sentences.size() < 4) {
            LungeRepDepthDto dropFrom = null, dropTo = null;
            int biggestDrop = 0;
            for (int i = 0; i + 1 < repDepths.size(); i++) {
                LungeRepDepthDto a = repDepths.get(i), b = repDepths.get(i + 1);
                if (a.getSide() == null || !a.getSide().equals(b.getSide())) continue;
                int drop = a.getDepthScore() - b.getDepthScore();
                boolean gotShallower = b.getMinKneeAngle() > a.getMinKneeAngle();
                if (a.getDepthScore() >= 85 && drop >= 15 && gotShallower && drop > biggestDrop) {
                    biggestDrop = drop;
                    dropFrom = a;
                    dropTo = b;
                }
            }
            if (dropFrom != null) {
                sentences.add(String.format(
                        "%d회차 자세는 좋았는데, %d회차는 조금 덜 앉았어요.",
                        dropFrom.getRepNumber(), dropTo.getRepNumber()));
            }
        }

        // ③ 통과는 했지만 목표선에 아슬아슬 — 세션 전체 평균이 얕은 쪽일 때 한 줄.
        if (sentences.size() < 4) {
            double overallAvg = repDepths.stream().mapToDouble(LungeRepDepthDto::getMinKneeAngle).average().orElse(0);
            boolean allPassed = repDepths.stream().allMatch(rep -> rep.getMinKneeAngle() <= TARGET);
            if (allPassed && overallAvg > CLOSE_CALL_KNEE) {
                sentences.add("목표선은 넘었지만 여유 있게 통과하지는 않았어요 — 조금만 더 앉아보세요.");
            }
        }

        // ④ 회차 간 편차가 큼.
        if (sentences.size() < 4 && repDepths.size() >= 3) {
            double mean = repDepths.stream().mapToDouble(LungeRepDepthDto::getMinKneeAngle).average().orElse(0);
            double variance = repDepths.stream()
                    .mapToDouble(r -> Math.pow(r.getMinKneeAngle() - mean, 2)).average().orElse(0);
            double stddev = Math.sqrt(variance);
            if (stddev > 10.0) {
                LungeRepDepthDto shallowest = repDepths.stream()
                        .max(Comparator.comparingDouble(LungeRepDepthDto::getMinKneeAngle)).orElseThrow();
                LungeRepDepthDto deepest = repDepths.stream()
                        .min(Comparator.comparingDouble(LungeRepDepthDto::getMinKneeAngle)).orElseThrow();
                sentences.add(String.format(
                        "회차마다 앉는 깊이가 들쭉날쭉했어요 — %d회차가 가장 얕았고 %d회차가 가장 깊었어요.",
                        shallowest.getRepNumber(), deepest.getRepNumber()));
            }
        }

        // ⑤ 폴백 — 다른 트리거가 하나도 없을 때만.
        if (sentences.isEmpty()) {
            sentences.add(String.format("%d회 모두 목표 깊이를 안정적으로 지켰어요.", repDepths.size()));
        }

        return sentences.size() > 4 ? sentences.subList(0, 4) : sentences;
    }

    private String formatTimestamp(Double timestampSec) {
        if (timestampSec == null) return "00:00";
        int totalSeconds = timestampSec.intValue();
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
