package com.shadowfit.model.exercise;

/**
 * 측정된 것이 없는 세션의 <b>사유</b> — AI 의 완료 통보({@code SessionCompleteRequest.measurement_status})에서 온다.
 *
 * <p>«측정됨» 은 이 enum 에 없다 — 저장하지 않고 컬럼을 {@code NULL} 로 둔다. 그래서 이 컬럼이 생기기 전 세션과
 * 이 필드를 모르는 구버전 AI 가 완료시킨 세션도 «측정됨» 으로 읽힌다(AI 가 보낸 값 0 = {@code MEASUREMENT_OK}).
 * {@code @Enumerated(EnumType.STRING)} 이라 상수 이름이 그대로 저장된다 — 이름을 바꾸면 기존 행을 못 읽는다.
 *
 * <p>proto 와 이름이 같다({@code MEASUREMENT_} 접두사만 뗀다). 앱은 이 문자열로 안내 문구를 가른다.
 */
public enum MeasurementStatus {
    /** 수락된 프레임이 하나도 없다 — 사람을 못 찾았거나 프레임이 오지 않았다. */
    NO_POSE,
    /** 프레임은 받았지만 판정에 쓸 수 있는 것이 없었다(하체 등이 화면 밖). */
    NOT_FULLY_VISIBLE,
    /** 판정된 프레임은 있으나 인정 반복·미인정 시도가 0. */
    NO_REP;

    /**
     * proto 값 → 저장할 값. «측정됨»(OK)·모르는 값(신버전 AI 가 새 사유를 보낸 경우 {@code UNRECOGNIZED})은 {@code null} 이다 —
     * 모르는 사유를 억지로 어느 칸에 넣지 않는다. 사용자에게는 «측정 안 됨» 의 일반 안내가 나가고, 구체 사유만 비어 있다.
     */
    public static MeasurementStatus fromProto(com.shadowfit.grpc.MeasurementStatus proto) {
        return switch (proto) {
            case MEASUREMENT_NO_POSE -> NO_POSE;
            case MEASUREMENT_NOT_FULLY_VISIBLE -> NOT_FULLY_VISIBLE;
            case MEASUREMENT_NO_REP -> NO_REP;
            case MEASUREMENT_OK, UNRECOGNIZED -> null;
        };
    }
}
