package com.shadowfit.model.notification;

/**
 * 알림 종류. {@code @Enumerated(EnumType.STRING)} 으로 {@code notifications.type VARCHAR(30)} 에
 * 상수 이름 그대로 저장된다 — DB ENUM 이 아닌 이유는 V17 헤더.
 *
 * <p>남발 방지 UNIQUE(sender, recipient, type, target_date)의 한 축이므로, 종류를 나누면
 * «같은 날 1회» 도 종류별로 따로 센다.
 */
public enum NotificationType {

    /** 재촉하기 — 같은 모임의 멤버가 «오늘 운동 안 한 사람» 에게 보낸다. 발신자가 곧 내용이라 payload 가 없다. */
    NUDGE
}
