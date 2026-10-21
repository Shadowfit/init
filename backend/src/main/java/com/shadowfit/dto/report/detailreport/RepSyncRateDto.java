package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회차(rep) 하나의 싱크로율 — 리포트의 회차별 추이 한 점.
 *
 * <p>싱크로율의 자연스러운 단위가 rep 인 이유: DTW 는 시퀀스 한 쌍을 받아 숫자 하나를 내놓는다.
 * 사람마다 운동 속도가 달라 프레임 번호를 1:1 로 맞댈 수 없어 시간축을 늘였다 줄여 정렬하므로,
 * 채점이 rep 단위로 한 번 일어난다(ai-server {@code _summarize_rep}).
 *
 * <p>{@code timeStamp} 는 그 rep 의 중앙 프레임 기준이다 — worst 구간의 대표 시각과 같은 규칙이라
 * 추이 그래프에서 worst 점을 짚을 때 값이 어긋나지 않는다.
 *
 * <p><b>«미인정 시도» 도 한 점이 된다 (2026-10-21, V35).</b> 횟수에 안 들어간 시도도 추이에 보여야 «좋은 자세만 세니
 * 리포트는 무조건 좋다» 가 안 된다. 미인정 점은 {@code counted=false}·{@code repNumber=null}·{@code attemptNumber=k}·
 * {@code rejectReason} 을 갖고, 목록 전체는 {@code timestampSec} 오름차순이다(인정·미인정이 시간순으로 섞인다).
 * 그래서 <b>{@code repNumber} 는 null 일 수 있다</b> — 회차로 무언가를 잇는 쪽(worst 매칭, 주간 B층 {@code JSON_TABLE})은
 * null 을 건너뛰어야 한다.
 *
 * <p>이 필드들이 생기기 전에 저장된 리포트(precompute JSON)는 셋이 없다 — 역직렬화하면 {@code counted} 는 필드 기본값
 * {@code true}(옛 점은 전부 인정 반복이었다), {@code timestampSec}·{@code attemptNumber}·{@code rejectReason} 은 null 이다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepSyncRateDto {
    private Integer repNumber;      // 인정 반복의 회차(1부터, 세션 전체 연번). 미인정 시도는 null
    private double syncRate;        // 소수 1자리로 반올림
    private String timeStamp;       // "01:15" — 인정 반복은 대표(가장 깊은) 프레임, 미인정 시도는 가장 깊이 앉은 순간
    private Double timestampSec;    // timeStamp 의 숫자 원본(정렬 기준, pose_data.timestamp_sec 원점). 옛 리포트는 null
    private boolean counted = true; // false = 미인정 시도. 옛 리포트(필드 없음)는 기본값 true 로 읽힌다
    private Integer attemptNumber;  // 미인정 시도 번호(1부터). 인정 반복은 null
    private String rejectReason;    // 미인정 사유(DEPTH_NOT_CONFIRMED | SPLIT_STANCE). 인정 반복은 null

    /** 인정 반복 한 점 — 숫자 시각 없이. 옛 형식과 같은 모양이다(테스트 픽스처·옛 리포트 재현용). */
    public RepSyncRateDto(Integer repNumber, double syncRate, String timeStamp) {
        this(repNumber, syncRate, timeStamp, null, true, null, null);
    }

    /** 인정 반복 한 점. */
    public static RepSyncRateDto counted(int repNumber, double syncRate, String timeStamp, Double timestampSec) {
        return new RepSyncRateDto(repNumber, syncRate, timeStamp, timestampSec, true, null, null);
    }

    /** 미인정 시도 한 점. */
    public static RepSyncRateDto rejected(int attemptNumber, double syncRate, String timeStamp, Double timestampSec,
                                          String rejectReason) {
        return new RepSyncRateDto(null, syncRate, timeStamp, timestampSec, false, attemptNumber, rejectReason);
    }
}
