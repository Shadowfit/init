package com.shadowfit.model.notification;

/**
 * 알림 종류. DB 에는 {@code VARCHAR(50)} 으로 저장한다({@code V17__add_notifications.sql} 머리 주석) —
 * 값 추가는 여기 한 줄이고 마이그레이션이 필요 없다.
 *
 * <p>하루 1회 제한(UNIQUE sender·recipient·type·date)은 <b>종류별</b>이다 — 같은 사람에게 재촉 한 번과
 * 응원 한 번은 같은 날 둘 다 된다.
 */
public enum NotificationType {
    /** 재촉하기 — «오늘 아직 안 한» 친구에게. 본문 없음. */
    NUDGE,
    /** 응원 보내기 — 정형 문구 또는 직접 입력 한 줄({@code Notification#message})을 싣는다. 완료한 친구에게도 보낸다. */
    CHEER
}
