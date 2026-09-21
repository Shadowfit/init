package com.shadowfit.service.notification;

import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.repository.notification.NotificationRepository;
import com.shadowfit.repository.notification.PushTokenRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 알림 INSERT 만 하는 트랜잭션 경계. {@link NotificationService#nudge} 가 별도 빈으로 떼어 둔 이유는
 * UNIQUE 위반을 <b>트랜잭션이 끝난 뒤</b> 잡기 위해서다 — flush 실패를 같은 트랜잭션 안에서 catch 하면
 * Hibernate 세션이 손상돼 후속 쿼리가 깨진다({@code GroupService.admit} 주석, {@code DailyLogRepository}
 * 실측). 이 메서드가 예외로 빠져나오면 트랜잭션은 이미 롤백돼 있고, 호출자는 그 예외를 409 로 옮기기만
 * 하면 된다.
 *
 * <p><b>푸시는 같은 트랜잭션에 아웃박스 행으로 얹는다</b>(§3-C c, §4-3 ②) — 알림이 커밋되면 통보 행도
 * 반드시 있고, 알림이 롤백되면(UNIQUE 위반) 통보 행도 없다. 실제 송신은 {@code OutboxPublisher} 가 진다.
 * 수신자에게 등록된 기기가 하나도 없으면 행을 안 만든다(§4-3 ④ c) — 앱 알림 권한을 안 준 회원은
 * 「실패」가 아니라 「대상 없음」이고, 그걸 FAILED 로 세면 지표가 소음이 된다.
 */
@Component
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository notificationRepository;
    private final PushTokenRepository pushTokenRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public Notification insert(Member sender, Member recipient, NotificationType type, LocalDate targetDate,
                               String message) {
        Notification saved = notificationRepository.saveAndFlush(Notification.builder()
                .sender(sender)
                .recipient(recipient)
                .type(type)
                .targetDate(targetDate)
                .message(message)
                .build());
        if (pushTokenRepository.existsByMemberId(recipient.getId())) {
            outboxEventRepository.save(OutboxEvent.pushNotification(saved.getId(), CorrelationIds.current()));
        }
        return saved;
    }
}
