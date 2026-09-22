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
        double syncSum = 0.0;
        double startedSec = 0.0;
        double endedSec = 0.0;

        for (RepSummaryProjection rep : repsInOrder) {
            int setNo = (rep.getRepNumber() + target - 1) / target; // ceil(rep / T)
            if (setNo != currentSetNo) {
                if (currentSetNo > 0) {
                    sets.add(build(session, currentSetNo, reps, syncSum, startedSec, endedSec));
                }
                currentSetNo = setNo;
                reps = 0;
                syncSum = 0.0;
                startedSec = rep.getStartedSec();
            }
            reps++;
            syncSum += rep.getAvgSyncRate();
            endedSec = rep.getEndedSec();
        }
        sets.add(build(session, currentSetNo, reps, syncSum, startedSec, endedSec));
        return sets;
    }

    private static SessionSet build(Session session, int setNo, int reps, double syncSum,
                                    double startedSec, double endedSec) {
        return SessionSet.builder()
                .session(session)
                .setNo(setNo)
                .reps(reps)
                // rep 가중 평균 — 각 rep 의 값이 이미 그 rep 프레임들의 평균이므로 여기선 rep 수로 나눈다
                .avgSyncRate(BigDecimal.valueOf(syncSum / reps).setScale(2, RoundingMode.HALF_UP))
                .startedSec(startedSec)
                .endedSec(endedSec)
                .build();
    }
}
