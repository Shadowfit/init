package com.shadowfit.service.exercise;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 세션 소모 칼로리 추정 (2026-10-21).
 *
 * <p><b>식</b>: kcal = MET × 3.5 × 체중(kg) ÷ 200 × 분 — ACSM 에너지 소비 공식(1 MET = 산소 3.5 mL/kg/분,
 * 산소 1 L ≈ 5 kcal 이라 kcal/분 = MET × 3.5 × kg ÷ 200).
 *
 * <p><b>MET 3.0</b> = 2024 Adult Compendium of Physical Activities 코드 <b>02056</b>
 * «Body weight resistance exercises (e.g., squat, lunge, push-up, crunch), general»
 * (https://pacompendium.com/conditioning-exercise). 스쿼트·런지가 둘 다 이 한 항목에 들어 있어 종목별로 나누지 않는다.
 *
 * <p><b>분</b> = 세션 시작~종료(리포트의 «운동 시간» 과 같은 구간)를 분 단위 소수로. 리포트·일지가 분을 정수로
 * 자르는 것과 달리 여기서는 자르지 않는다 — 짧은 세션(2~3분)에서 절삭이 칼로리를 30% 넘게 깎는다.
 *
 * <p><b>왜 Spring 이 계산하나</b>: AI 는 체중을 모른다. 그래서 완료 콜백의 {@code calories_burned} 를 늘 0.0 으로
 * 보낸다({@code ai-server/app/grpc/exercise_servicer.py} «칼로리 계산은 추후»). 체중({@code members.weight})을
 * 가진 쪽이 계산하고, proto 필드는 그대로 두되 값은 무시한다.
 *
 * <p><b>체중을 모르면 0 이 아니라 {@code null}</b>(«측정 안 됨») — 0 을 쓰면 «0 kcal 소모» 로 둔갑해 지난번 대비
 * 차이·주간 합계를 왜곡한다. 싱크로율의 null 규약(이슈 #75)과 같은 이유다.
 */
@Component
public class CalorieEstimator {

    /** Compendium 2024 코드 02056 — 맨몸 저항 운동(스쿼트·런지 등), general. */
    static final double MET_BODY_WEIGHT_RESISTANCE = 3.0;

    /**
     * {@code exercise_sessions.calories_burned} 가 {@code DECIMAL(7,2)} 라 담을 수 있는 최댓값. 넘는 값을 쓰면
     * 완료 트랜잭션이 통째로 롤백된다 — 체중 입력에 범위 검증이 없어(오타로 7000kg 도 들어간다) 방어한다.
     */
    private static final BigDecimal COLUMN_MAX = new BigDecimal("99999.99");

    /**
     * @return 소수 둘째 자리로 반올림한 kcal. 체중이 없거나 0 이하, 시각이 없거나, 컬럼에 안 들어가는 값이면 {@code null}.
     *         종료가 시작보다 앞서면(시계 어긋남) 0분으로 본다.
     */
    public BigDecimal estimate(Double weightKg, LocalDateTime start, LocalDateTime end) {
        if (weightKg == null || weightKg <= 0 || start == null || end == null) {
            return null;
        }
        double minutes = Math.max(0, Duration.between(start, end).toMillis()) / 60_000.0;
        double kcal = MET_BODY_WEIGHT_RESISTANCE * 3.5 * weightKg / 200.0 * minutes;
        BigDecimal rounded = BigDecimal.valueOf(kcal).setScale(2, RoundingMode.HALF_UP);
        return rounded.compareTo(COLUMN_MAX) > 0 ? null : rounded;
    }
}
