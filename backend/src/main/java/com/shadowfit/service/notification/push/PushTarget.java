package com.shadowfit.service.notification.push;

import com.shadowfit.model.notification.NotificationType;

import java.util.List;

/**
 * 푸시 한 번에 필요한 것만 트랜잭션 밖으로 들고 나온 불변 스냅샷 — 엔티티를 트랜잭션 밖에서 만지지 않기 위해.
 *
 * @param senderName 보낸 사람 표시 이름. 탈퇴해서 {@code sender_id} 가 NULL 이면 {@code null}
 * @param message    응원 본문(CHEER). 재촉(NUDGE)은 {@code null}
 * @param tokens     수신자의 기기 토큰. 비어 있을 수 있다(적재 뒤 로그아웃)
 */
public record PushTarget(Long notificationId, NotificationType type, String senderName, String message,
                         List<String> tokens) {
}
