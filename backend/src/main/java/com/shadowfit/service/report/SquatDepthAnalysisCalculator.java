package com.shadowfit.service.report;

import com.shadowfit.dto.report.PoseFrameGrouping;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepDepthDto;
import com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SquatDepthMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 스쿼트 깊이 정확도 채점 + 회차별 한국어 코멘트 (2026-09-21).
 *
 * <p>{@link SessionAnalysisCalculator}의 형제 클래스로 따로 뒀다 — 그 클래스는 rep으로 묶는 것 말고는
 * 종목을 모르는(exercise-agnostic) 설계라(어느 운동이든 sync_rate 최저 rep을 고를 뿐이다), 스쿼트
 * 전용 깊이 밴드·모드 판정을 끼워 넣으면 그 중립성이 깨진다. rep 그룹핑 규칙만
 * {@link PoseFrameGrouping#byRep}로 공유한다.
 *
 * <p>🔴 <b>HALF 모드의 목표 밴드(115~140도)는 실촬영 하프 스쿼트 영상으로 검증되기 전까지
 * 잠정값이다.</b> ai-server의 {@code HALF_SQUAT_THRESHOLDS}(bottom_hip·min_hip_drop)와 마찬가지로
 * 비례 추정이며, 두 값이 서로 다른 숫자를 매기지 않도록 개념적으로 연결돼 있다는 점만 여기 남긴다 —
 * 밴드가 바뀌면 {@code ai-server/app/core/squat_counter.py}의 프리셋도 함께 재검토해야 한다.
 * 자세한 근거는 {@code docs/decisions/squat-repetition-counting.md}의 2026-09-21 절 참고.
 */
@Component
public class SquatDepthAnalysisCalculator {

    // V2__seed_master_data.sql: 1=스쿼트. analyzer_registry.py의 _EXERCISE_ID_TO_TYPE와 같은 결합
    // 취약성을 그대로 안는다 — 시드가 바뀌면 이 값도 같이 고쳐야 한다.
    private static final long SQUAT_EXERCISE_ID = 1L;

    private static final double FULL_TARGET = 100.0;
    private static final double FULL_ENTRY = 115.0;   // ai-server bottom_knee(100) + margin(15)
    private static final double HALF_CENTER = 127.5;  // (115+140)/2
    private static final double HALF_HALF_WIDTH = 12.5;

    public SquatDepthAnalysisDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        if (session.getExercise().getId() != SQUAT_EXERCISE_ID) {
            return null;
        }
        if (poseFrames == null || poseFrames.isEmpty()) {
            return null;
        }

        SquatDepthMode mode = session.getSquatDepthMode();
        List<RepDepthDto> repDepths = new ArrayList<>();
        for (Map.Entry<Integer, List<PoseFrameProjection>> entry : PoseFrameGrouping.byRep(poseFrames).entrySet()) {
            RepDepthDto repDepth = scoreRep(entry.getKey(), entry.getValue(), mode);
            if (repDepth != null) {
                repDepths.add(repDepth);
            }
        }
        if (repDepths.isEmpty()) {
            return null; // 유효한 무릎각 데이터가 있는 rep이 하나도 없음
        }

        int sessionScore = (int) Math.round(
                repDepths.stream().mapToInt(RepDepthDto::getDepthScore).average().orElse(0.0));

        SquatDepthAnalysisDto dto = new SquatDepthAnalysisDto();
        dto.setDepthMode(mode.name());
        dto.setDepthScore(sessionScore);
        dto.setDepthGrade(gradeOf(sessionScore));
        dto.setRepDepths(repDepths);
        dto.setNarrative(buildNarrative(repDepths, mode));
        return dto;
    }

    /** 이 rep에서 가장 깊게 앉은 순간(최소 smoothedKneeAngle)을 채점한다. 유효값이 없으면 null. */
    private RepDepthDto scoreRep(int repNumber, List<PoseFrameProjection> frames, SquatDepthMode mode) {
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
        if (deepest == null) {
            return null;
        }
        double minKnee = deepest.smoothedKneeAngle();
        int score = mode == SquatDepthMode.HALF ? scoreHalf(minKnee) : scoreFull(minKnee);
        return new RepDepthDto(repNumber, minKnee, score, formatTimestamp(deepest.timestampSec()));
    }

    /** FULL: 100도 이하는 만점, 100~115도는 100→60 선형, 그 너머는 방어적으로 계속 깎는다. */
    private int scoreFull(double minKnee) {
        if (minKnee <= FULL_TARGET) {
            return 100;
        }
        if (minKnee <= FULL_ENTRY) {
            return (int) Math.round(100 - (minKnee - FULL_TARGET) / (FULL_ENTRY - FULL_TARGET) * 40);
        }
        return (int) Math.max(0, Math.round(60 - (minKnee - FULL_ENTRY) * 2));
    }

    /** HALF: 115~140도 밴드 중심(127.5)에서 만점, 밴드 경계(±12.5)에서 70점, 그 너머는 계속 깎는다. */
    private int scoreHalf(double minKnee) {
        double dist = Math.abs(minKnee - HALF_CENTER);
        if (dist <= HALF_HALF_WIDTH) {
            return (int) Math.round(100 - dist / HALF_HALF_WIDTH * 30);
        }
        return (int) Math.max(0, Math.round(70 - (dist - HALF_HALF_WIDTH) * 2));
    }

    private String gradeOf(int score) {
        if (score >= 90) return "EXCELLENT";
        if (score >= 75) return "GOOD";
        if (score >= 60) return "FAIR";
        if (score >= 40) return "NEEDS_WORK";
        return "POOR";
    }

    /**
     * 회차별 한국어 코멘트, 최대 4문장. 리포트를 짧게 유지하려고 트리거된 것만 우선순위 순으로 채운다.
     *
     * <p>우선순위: ① 인접 회차 대비(앞 회차 ≥85점, 바로 다음 회차가 15점 이상 낮음) &gt;
     * ② HALF 모드에서 풀 스쿼트급으로 더 깊이 앉은 회차(minKnee &lt; 115) &gt;
     * ③ 밴드 얕은 쪽 경계 근접 회차(①에서 이미 다룬 회차는 제외) &gt;
     * ④ 회차 간 편차가 큼(stddev &gt; 10도) &gt; ⑤ 전부 양호(다른 트리거가 없을 때만, 폴백).
     */
    private List<String> buildNarrative(List<RepDepthDto> repDepths, SquatDepthMode mode) {
        List<String> sentences = new ArrayList<>();
        Set<Integer> mentioned = new HashSet<>();
        String modeLabel = mode == SquatDepthMode.HALF ? "하프 스쿼트" : "풀 스쿼트";

        // ① 인접 회차 대비 — 가장 큰 낙차 한 쌍만. 점수만 보면 안 된다: HALF 모드는 밴드에서 멀어질수록
        // (얕아지든 깊어지든) 점수가 떨어지는 대칭 공식이라, 점수 하락만으로는 "더 깊이 앉아서" 떨어진
        // 경우까지 "덜 앉았어요"로 잘못 말할 수 있다 — minKneeAngle이 실제로 더 커진(=더 얕아진)
        // 경우에만 이 문구를 쓴다. 더 깊어져서 떨어진 경우는 ②(HALF 전용)가 담당한다.
        RepDepthDto dropFrom = null, dropTo = null;
        int biggestDrop = 0;
        for (int i = 0; i + 1 < repDepths.size(); i++) {
            RepDepthDto a = repDepths.get(i), b = repDepths.get(i + 1);
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
                    "%d회차 자세는 좋았는데, %d회차는 %s보다 조금 덜 앉았어요.",
                    dropFrom.getRepNumber(), dropTo.getRepNumber(), modeLabel));
            mentioned.add(dropFrom.getRepNumber());
            mentioned.add(dropTo.getRepNumber());
        }

        // ② HALF 모드에서 풀 스쿼트급으로 더 깊이 앉은 회차 — 가장 깊었던 한 회차만.
        if (mode == SquatDepthMode.HALF && sentences.size() < 4) {
            RepDepthDto deepest = null;
            for (RepDepthDto rep : repDepths) {
                if (rep.getMinKneeAngle() < FULL_ENTRY && !mentioned.contains(rep.getRepNumber())
                        && (deepest == null || rep.getMinKneeAngle() < deepest.getMinKneeAngle())) {
                    deepest = rep;
                }
            }
            if (deepest != null) {
                sentences.add(String.format(
                        "%d회차는 하프 스쿼트 목표보다 더 깊이 앉았어요. 풀 스쿼트에 가까운 깊이였어요.",
                        deepest.getRepNumber()));
                mentioned.add(deepest.getRepNumber());
            }
        }

        // ③ 밴드 얕은 쪽 경계에 가장 가까운(=가장 얕은) 회차 — ①에서 다룬 회차는 제외.
        if (sentences.size() < 4) {
            RepDepthDto shallowest = null;
            for (RepDepthDto rep : repDepths) {
                if (mentioned.contains(rep.getRepNumber())) continue;
                if (rep.getDepthScore() < 60
                        && (shallowest == null || rep.getMinKneeAngle() > shallowest.getMinKneeAngle())) {
                    shallowest = rep;
                }
            }
            if (shallowest != null) {
                sentences.add(String.format("%d회차는 목표 깊이보다 얕았어요.", shallowest.getRepNumber()));
                mentioned.add(shallowest.getRepNumber());
            }
        }

        // ④ 회차 간 편차가 큼.
        if (sentences.size() < 4 && repDepths.size() >= 3) {
            double mean = repDepths.stream().mapToDouble(RepDepthDto::getMinKneeAngle).average().orElse(0);
            double variance = repDepths.stream()
                    .mapToDouble(r -> Math.pow(r.getMinKneeAngle() - mean, 2)).average().orElse(0);
            double stddev = Math.sqrt(variance);
            if (stddev > 10.0) {
                RepDepthDto shallowest = repDepths.stream()
                        .max(Comparator.comparingDouble(RepDepthDto::getMinKneeAngle)).orElseThrow();
                RepDepthDto deepest = repDepths.stream()
                        .min(Comparator.comparingDouble(RepDepthDto::getMinKneeAngle)).orElseThrow();
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
