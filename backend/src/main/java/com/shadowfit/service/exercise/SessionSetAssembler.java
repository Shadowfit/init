package com.shadowfit.service.exercise;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SessionSet;
import com.shadowfit.repository.exercise.PoseDataRepository.RepSummaryProjection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * rep 목록 → 세트 목록. 순수 함수 — DB 도 시계도 없다.
 *
 * <p><b>세트 경계는 «rep 이 목표에 닿는 순간» 하나뿐이다</b> (lunge-and-set-backend.md §7). 그래서 rep {@code r}
 * 의 세트는 {@code ceil(r / T)} 이고, 휴식 간격 같은 상수가 끼어들 자리가 없다. 같은 이유로 이 계산은 AI 의
 * 실시간 판정과 무관하게 저장본({@code pose_data})만으로 재현된다 — 싱크 통계(#75)와 같은 원칙.
 *
 * <p>rep 번호에 빈틈이 있으면(다운샘플·유실로 어떤 rep 의 행이 하나도 안 남은 경우) 그 rep 은 세지 않지만
 * <b>세트 번호는 rep 번호로 정한다</b>. 즉 rep 13 이 없어도 rep 14 는 2세트(T=12)에 들어간다 — «몇 번째
 * 세트인가» 는 AI 가 매긴 rep 번호의 함수이지, 남은 행 수의 함수가 아니다.
 *
 * <p>세트 평균은 <b>채점된 rep 만</b> 으로 낸다. 횟수만 세는 종목({@code ExerciseAnalysisCapabilities.isCountOnly})
 * 은 sync_rate 가 없어 rep 평균이 null 로 오는데, 그 세트의 평균은 0 이 아니라 null 이다(V33) — 세션 통계
 * ({@code SessionCompletionTx.resolveSyncStats})와 같은 «미측정 ≠ 0%» 규약. 횟수·시각은 채점과 무관하게 남긴다.
 */
public final class SessionSetAssembler {

    private SessionSetAssembler() {
    }

    /**
     * @param session          {@code targetRepsPerSet} 을 읽는다. null 이면(세트 도입 전 세션) 빈 목록
     * @param repsInOrder      {@code PoseDataRepository.findRepSummaries} 결과 — rep 번호 오름차순, {@code repNumber > 0}
     */
    public static List<SessionSet> assemble(Session session, List<RepSummaryProjection> repsInOrder) {
        Integer target = session.getTargetRepsPerSet();
        if (target == null || target < 1 || repsInOrder.isEmpty()) {
            return List.of();
        }

        List<SessionSet> sets = new ArrayList<>();
        int currentSetNo = 0;
        int reps = 0;
        int scoredReps = 0;
        double syncSum = 0.0;
        double startedSec = 0.0;
        double endedSec = 0.0;

        for (RepSummaryProjection rep : repsInOrder) {
            int setNo = (rep.getRepNumber() + target - 1) / target; // ceil(rep / T)
            if (setNo != currentSetNo) {
                if (currentSetNo > 0) {
                    sets.add(build(session, currentSetNo, reps, scoredReps, syncSum, startedSec, endedSec));
                }
                currentSetNo = setNo;
                reps = 0;
                scoredReps = 0;
                syncSum = 0.0;
                startedSec = rep.getStartedSec();
            }
            reps++;
            // 채점 없는 rep(횟수만 세는 종목·sync_rate 전부 null)은 평균에서 빼고 횟수만 센다
            if (rep.getAvgSyncRate() != null) {
                scoredReps++;
                syncSum += rep.getAvgSyncRate();
            }
            endedSec = rep.getEndedSec();
        }
        sets.add(build(session, currentSetNo, reps, scoredReps, syncSum, startedSec, endedSec));
        return sets;
    }

    private static SessionSet build(Session session, int setNo, int reps, int scoredReps, double syncSum,
                                    double startedSec, double endedSec) {
        return SessionSet.builder()
                .session(session)
                .setNo(setNo)
                .reps(reps)
                // rep 가중 평균 — 각 rep 의 값이 이미 그 rep 프레임들의 평균이므로 여기선 채점된 rep 수로 나눈다.
                // 채점된 rep 이 없으면 null(«미측정 ≠ 0%»).
                .avgSyncRate(scoredReps == 0
                        ? null
                        : BigDecimal.valueOf(syncSum / scoredReps).setScale(2, RoundingMode.HALF_UP))
                .startedSec(startedSec)
                .endedSec(endedSec)
                .build();
    }
}
