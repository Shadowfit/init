package com.shadowfit.model.notification;

/**
 * 알림 종류. DB 에는 {@code VARCHAR(50)} 으로 저장한다({@code V17__add_notifications.sql} 머리 주석) —
 * 값 추가는 여기 한 줄이고 마이그레이션이 필요 없다.
 *
 * <p>지금은 재촉 하나뿐이다. 응원·리액션 알림은 각 기능(§4-1 #11 등)이 생길 때 그쪽 PR 에서 추가한다.
 */
public enum NotificationType {
    /** 재촉하기 — «오늘 아직 안 한» 친구에게. 같은 사람에게 하루 1회. */
    NUDGE
}
