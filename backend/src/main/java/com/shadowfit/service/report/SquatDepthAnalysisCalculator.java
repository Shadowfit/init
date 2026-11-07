package com.shadowfit.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.PoseFrameGrouping;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.RejectedAttemptProjection;
import com.shadowfit.dto.report.detailreport.RepDepthDto;
import com.shadowfit.dto.report.detailreport.SquatDepthAnalysisDto;
import com.shadowfit.model.exercise.RejectedAttemptReason;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SquatDepthMode;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.service.exercise.ReferenceDepth;
import lombok.RequiredArgsConstructor;
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
 *
 * <p><b>FULL 모드 목표는 기준(시범) 깊이에서 유도한다 (2026-10-21).</b> 예전엔 «무릎 100도 이하 만점, 115도까지
 * 100→60 선형» 이었는데, AI 는 무릎 115도 이하라야 반복으로 세므로 센 회차는 전부 60점 이상·거의 100점이었다.
 * 그 사이 AI 싱크로율은 기준(3D 마네킹 시범, 최저 무릎 71.2°)의 최저점과 비교하게 바뀌어(2026-10-20, 2D 비율 보정
 * 각도) 실세션에서 무릎 85°까지만 앉은 회차가 싱크로율 63.5(«자세 보정 필요»)인데 깊이는 100점(«매우 정확해요»)
 * 으로 서로 모순됐다. 100도 선은 2D 전환 전에 정한 값이라 다시 맞춘 적이 없었다. 그래서 지금은
 * <ul>
 *   <li>목표 = 기준 최저 무릎각({@link ReferenceDepth#measure}, AI 와 같은 2D 비율 보정 각도) + {@link #KNEE_TOLERANCE_DEG}</li>
 *   <li>회차 점수 = max(0, round(100 − {@value #POINTS_PER_DEG} × max(0, 최저 무릎각 − 목표))) — 목표보다 1도 얕을 때마다 4점</li>
 * </ul>
 * 목표 76.2°(현재 기준)면 81°→81점, 85.3°→64점, 93°→33점, 100°→5점. 예전보다 일부러 엄격하다(제품 결정).
 * HALF 모드는 그대로다(앱이 더는 HALF 를 고르게 하지 않는다).
 */
@Component
@RequiredArgsConstructor
public class SquatDepthAnalysisCalculator {

    // V2__seed_master_data.sql: 1=스쿼트. analyzer_registry.py의 _EXERCISE_ID_TO_TYPE와 같은 결합
    // 취약성을 그대로 안는다 — 시드가 바뀌면 이 값도 같이 고쳐야 한다.
    private static final long SQUAT_EXERCISE_ID = 1L;

    /**
     * FULL 목표 = 기준 최저 무릎각 + 이 값(도). AI 싱크로율의 최저점 무릎 허용 오차
     * ({@code ai-server/app/core/dtw_calculator.py _KNEE_TOLERANCE_DEG})와 <b>같은 값</b>이다 —
     * 🔴 둘은 반드시 함께 바꾼다. 어긋나면 «싱크로율은 깊이 감점 없음인데 깊이 점수는 감점» (또는 그 반대) 이 다시 생긴다.
     */
    static final double KNEE_TOLERANCE_DEG = 5.0;

    /**
     * 기준이 없거나(행 없음) 잴 수 없을 때(랜드마크·JSON 깨짐) 쓰는 기준 최저 무릎각 — 현재 기준인 3D 마네킹
     * 시범의 실측값(2026-10-21, {@link ReferenceDepth} 로 잰 값). 기준 영상을 바꾸면 이 값도 다시 재서 맞춘다.
     */
    static final double FALLBACK_REFERENCE_MIN_KNEE_DEG = 71.2;

    /** 목표보다 1도 얕을 때마다 깎는 점수 — 목표 +25도에서 0점. */
    static final double POINTS_PER_DEG = 4.0;

    /** 코멘트에서 «좋았던 회차» 로 치는 점수 — ①(인접 낙차의 앞 회차)과 ⑤(«지켰어요» 폴백)가 같은 선을 쓴다. */
    static final int GOOD_REP_SCORE = 85;

    /** 코멘트 최대 문장 수 — 미인정 시도 문장(사유별 하나)이 있으면 그만큼 회차 코멘트 몫이 준다. */
    static final int MAX_SENTENCES = 4;

    // FULL 채점에는 더 안 쓴다(2026-10-21) — AI 가 반복으로 세는 경계로서 HALF 코멘트 ②(«풀 스쿼트급») 판정에만 남았다.
    private static final double FULL_ENTRY = 115.0;   // ai-server bottom_knee(100) + margin(15)
    private static final double HALF_CENTER = 127.5;  // (115+140)/2
    private static final double HALF_HALF_WIDTH = 12.5;

    private final ExerciseReferenceRepository exerciseReferenceRepository;
    private final ObjectMapper objectMapper;

    public SquatDepthAnalysisDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        return calculate(session, poseFrames, List.of());
    }

    /**
     * 인정 반복 + «미인정 시도» (2026-10-21, V35).
     *
     * <p>미인정 시도도 <b>같은 공식</b>(같은 목표·같은 감점)으로 채점해 {@code counted=false} 행으로 넣고, 세션 점수는 모든
     * 행의 평균이다 — 얕아서 인정되지 않은 시도가 깊이 점수에서 빠지면 «좋은 자세만 세니 무조건 좋다» 가 된다. 무릎각이
     * 미상(null)인 시도는 채점할 근거가 없어 행을 안 만든다(인정 반복의 «유효값 없음» 과 같다). 미인정 시도가 있으면 행은
     * 시간 오름차순이고, 없으면 예전과 같은 rep 오름차순이다.
     *
     * <p>코멘트의 회차 지목(①~⑤)은 인정 반복만 본다 — «N회차» 는 인정 반복의 번호다. 미인정 시도는 사유별 한 문장으로
     * 따로 말한다({@link #rejectedSentences}).
     */
    public SquatDepthAnalysisDto calculate(Session session, List<PoseFrameProjection> poseFrames,
                                           List<RejectedAttemptProjection> rejectedAttempts) {
        if (session.getExercise().getId() != SQUAT_EXERCISE_ID) {
            return null;
        }
        List<RejectedAttemptProjection> rejected = rejectedAttempts == null ? List.of() : rejectedAttempts;
        if ((poseFrames == null || poseFrames.isEmpty()) && rejected.isEmpty()) {
            return null;
        }

        SquatDepthMode mode = session.getSquatDepthMode();
        Double targetKnee = mode == SquatDepthMode.HALF ? null : fullTargetKneeDeg(session.getExercise().getId());
        List<TimedDepth> counted = new ArrayList<>();
        if (poseFrames != null) {
            for (Map.Entry<Integer, List<PoseFrameProjection>> entry : PoseFrameGrouping.byRep(poseFrames).entrySet()) {
                TimedDepth repDepth = scoreRep(entry.getKey(), entry.getValue(), mode, targetKnee);
                if (repDepth != null) {
                    counted.add(repDepth);
                }
            }
        }
        List<TimedDepth> notCounted = new ArrayList<>();
        for (RejectedAttemptProjection attempt : rejected) {
            Double minKnee = attempt.minKneeAngle();
            if (minKnee == null || minKnee <= 0.0) {
                continue; // 미상 — 채점할 근거가 없다
            }
            notCounted.add(new TimedDepth(attempt.timestampSec(), RepDepthDto.rejected(
                    attempt.attemptNo(), minKnee, score(minKnee, mode, targetKnee), formatTimestamp(attempt.timestampSec()))));
        }
        if (counted.isEmpty() && notCounted.isEmpty()) {
            return null; // 유효한 무릎각 데이터가 있는 rep·시도가 하나도 없음
        }
        List<RepDepthDto> repDepths = mergeByTime(counted, notCounted);

        int sessionScore = (int) Math.round(
                repDepths.stream().mapToInt(RepDepthDto::getDepthScore).average().orElse(0.0));

        List<String> narrative = new ArrayList<>();
        List<String> rejectedLines = rejectedSentences(rejected, targetKnee);
        if (!counted.isEmpty()) {
            narrative.addAll(buildNarrative(counted.stream().map(TimedDepth::dto).toList(), mode,
                    MAX_SENTENCES - rejectedLines.size()));
        }
        narrative.addAll(rejectedLines);

        SquatDepthAnalysisDto dto = new SquatDepthAnalysisDto();
        dto.setDepthMode(mode.name());
        dto.setDepthScore(sessionScore);
        dto.setDepthGrade(gradeOf(sessionScore));
        dto.setRepDepths(repDepths);
        dto.setNarrative(narrative);
        dto.setTargetKneeDeg(targetKnee);
        return dto;
    }

    /** 정렬 기준 시각을 붙인 깊이 행 — {@link RepDepthDto} 는 «mm:ss» 문자열만 갖고 있어 숫자 시각을 따로 든다. */
    private record TimedDepth(Double timestampSec, RepDepthDto dto) {}

    /**
     * 미인정 시도가 없으면 인정 반복 그대로(rep 오름차순 — 예전과 같다), 있으면 둘을 시간 오름차순으로 섞는다.
     * 안정 정렬이라 같은 시각이면 인정 반복이 먼저다.
     */
    private static List<RepDepthDto> mergeByTime(List<TimedDepth> counted, List<TimedDepth> notCounted) {
        if (notCounted.isEmpty()) {
            return new ArrayList<>(counted.stream().map(TimedDepth::dto).toList());
        }
        List<TimedDepth> all = new ArrayList<>(counted);
        all.addAll(notCounted);
        all.sort(Comparator.comparing(TimedDepth::timestampSec, Comparator.nullsLast(Comparator.naturalOrder())));
        return new ArrayList<>(all.stream().map(TimedDepth::dto).toList());
    }

    /**
     * 미인정 시도를 사유별 한 문장으로 — 0 인 사유는 말하지 않는다. 바닥 미확인 문장은 «어떻게 하면 인정되는가» 까지 말한다:
     * FULL 이면 채점 목표 무릎각(앱 표시와 같은 반올림), HALF 는 목표 각도가 없어 «목표 깊이».
     */
    private static List<String> rejectedSentences(List<RejectedAttemptProjection> rejected, Double targetKnee) {
        if (rejected.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> byReason = RejectedAttemptProjection.countByReason(rejected);
        List<String> sentences = new ArrayList<>();
        Integer shallow = byReason.get(RejectedAttemptReason.DEPTH_NOT_CONFIRMED.name());
        if (shallow != null) {
            String howTo = targetKnee != null
                    ? String.format("무릎 %d° 이하까지 앉아 잠깐 멈췄다 일어나세요.", Math.round(targetKnee))
                    : "목표 깊이까지 앉아 잠깐 멈췄다 일어나세요.";
            sentences.add(String.format("%d회는 바닥 자세가 확인되지 않아 인정되지 않았어요 — %s", shallow, howTo));
        }
        Integer split = byReason.get(RejectedAttemptReason.SPLIT_STANCE.name());
        if (split != null) {
            sentences.add(String.format(
                    "%d회는 두 발이 앞뒤로 벌어진 자세로 보여 인정되지 않았어요 — 두 발을 어깨너비로 나란히 두고 앉으세요.", split));
        }
        return sentences;
    }

    /**
     * FULL 목표 무릎각 = 이 종목 기준의 최저 무릎각 + {@link #KNEE_TOLERANCE_DEG}, 소수 첫째 자리.
     *
     * <p>기준 행은 AI 에 보내는 것과 같은 조회({@code findByExerciseId}, 캐시됨 — 기준이 바뀌면 evict)라 AI 가 싱크로율에
     * 쓰는 기준과 같은 벌이다. 화면에 보이는 목표(소수 첫째 자리)와 채점에 쓰는 목표가 갈리지 않게 반올림한 값으로 채점한다.
     */
    private double fullTargetKneeDeg(Long exerciseId) {
        Double measured = ReferenceDepth.measure(
                exerciseReferenceRepository.findByExerciseId(exerciseId), objectMapper).minKneeDeg();
        double referenceMinKnee = measured != null ? measured : FALLBACK_REFERENCE_MIN_KNEE_DEG;
        return Math.round((referenceMinKnee + KNEE_TOLERANCE_DEG) * 10) / 10.0;
    }

    /** 이 rep에서 가장 깊게 앉은 순간(최소 smoothedKneeAngle)을 채점한다. 유효값이 없으면 null. */
    private TimedDepth scoreRep(int repNumber, List<PoseFrameProjection> frames, SquatDepthMode mode, Double targetKnee) {
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
        return new TimedDepth(deepest.timestampSec(), new RepDepthDto(
                repNumber, minKnee, score(minKnee, mode, targetKnee), formatTimestamp(deepest.timestampSec())));
    }

    /** 회차 점수 — 인정 반복과 미인정 시도가 같은 공식을 쓴다. */
    private int score(double minKnee, SquatDepthMode mode, Double targetKnee) {
        return mode == SquatDepthMode.HALF ? scoreHalf(minKnee) : scoreFull(minKnee, targetKnee);
    }

    /** FULL: 목표 이하(더 깊음)는 만점, 목표보다 1도 얕을 때마다 {@value #POINTS_PER_DEG}점씩 깎고 0점 아래로는 안 간다. */
    private int scoreFull(double minKnee, double targetKnee) {
        double shallowerBy = Math.max(0.0, minKnee - targetKnee);
        return (int) Math.max(0, Math.round(100 - POINTS_PER_DEG * shallowerBy));
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
     * ④ 회차 간 편차가 큼(stddev &gt; 10도) &gt; ⑤ 폴백(다른 트리거가 없을 때만).
     *
     * <p>⑤ 는 2026-10-21 부터 FULL 에서 둘로 갈린다 — 전 회차 ≥{@value #GOOD_REP_SCORE}점이면 «지켰어요», 아니면
     * 못 미친 회차 수를 말한다. 목표가 시범 깊이로 엄격해지면서 «전부 60~84점(FAIR~GOOD)·편차 작음» 세션이 흔해졌는데,
     * 그때 «목표 깊이를 안정적으로 지켰어요» 라고 하면 점수와 문장이 서로 모순된다. HALF 는 예전 문장 그대로다.
     *
     * @param repDepths    인정 반복만(미인정 시도 제외, rep 오름차순) — «N회차» 는 인정 반복의 번호다
     * @param maxSentences 이 메서드가 쓸 수 있는 문장 수. 미인정 시도 문장이 뒤에 붙으면 그만큼 줄어든다(합계 ≤ 4)
     */
    private List<String> buildNarrative(List<RepDepthDto> repDepths, SquatDepthMode mode, int maxSentences) {
        List<String> sentences = new ArrayList<>();
        Set<Integer> mentioned = new HashSet<>();
        // FULL 목표는 이제 «풀 스쿼트» 라는 일반 기준이 아니라 기준 영상(시범)의 깊이다(2026-10-21).
        String modeLabel = mode == SquatDepthMode.HALF ? "하프 스쿼트" : "시범 깊이";

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
            if (a.getDepthScore() >= GOOD_REP_SCORE && drop >= 15 && gotShallower && drop > biggestDrop) {
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
        if (mode == SquatDepthMode.HALF && sentences.size() < maxSentences) {
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
        if (sentences.size() < maxSentences) {
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
        if (sentences.size() < maxSentences && repDepths.size() >= 3) {
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

        // ⑤ 폴백 — 다른 트리거가 하나도 없을 때만. FULL 은 «지켰다» 고 말할 수 있을 때만 그렇게 말한다(메서드 주석).
        if (sentences.isEmpty()) {
            long shortReps = repDepths.stream().filter(r -> r.getDepthScore() < GOOD_REP_SCORE).count();
            if (mode == SquatDepthMode.HALF || shortReps == 0) {
                sentences.add(String.format("%d회 모두 목표 깊이를 안정적으로 지켰어요.", repDepths.size()));
            } else {
                sentences.add(String.format("%d회 중 %d회는 시범 깊이에 조금 못 미쳤어요.", repDepths.size(), shortReps));
            }
        }

        return sentences.size() > maxSentences ? sentences.subList(0, maxSentences) : sentences;
    }

    private String formatTimestamp(Double timestampSec) {
        if (timestampSec == null) return "00:00";
        int totalSeconds = timestampSec.intValue();
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
