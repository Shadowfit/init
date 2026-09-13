package com.shadowfit.service.notification.push;

import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.PushToken;
import com.shadowfit.repository.notification.NotificationRepository;
import com.shadowfit.repository.notification.PushTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 푸시 발행의 DB 걸음 둘 — 읽기(대상 조립)와 쓰기(죽은 토큰 삭제)를 각각 짧은 트랜잭션으로.
 * 그 사이의 HTTP 는 트랜잭션 밖이다({@code OutboxPublisher} 의 «선점 → 송신 → 기록» 과 같은 이유).
 * 별도 빈인 이유는 {@code OutboxEventStore} 와 같다 — 자기호출은 {@code @Transactional} 을 우회한다(#175).
 */
@Component
@RequiredArgsConstructor
public class PushDispatchStore {

    private final NotificationRepository notificationRepository;
    private final PushTokenRepository pushTokenRepository;

    /** 알림이 없으면 empty — 적재 뒤 수신자가 탈퇴해 CASCADE 로 사라진 경우. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PushTarget> load(Long notificationId) {
        return notificationRepository.findById(notificationId).map(this::toTarget);
    }

    private PushTarget toTarget(Notification n) {
        List<String> tokens = pushTokenRepository.findAllByMemberId(n.getRecipient().getId()).stream()
                .map(PushToken::getToken)
                .toList();
        String senderName = n.getSender() == null ? null : n.getSender().getUsername();
        return new PushTarget(n.getId(), n.getType(), senderName, tokens);
    }

    /** Expo 가 {@code DeviceNotRegistered} 로 답한 토큰을 지운다(§4-2 ②). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forgetDeadTokens(List<String> tokens) {
        tokens.forEach(pushTokenRepository::deleteByToken);
    }
}
