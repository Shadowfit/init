package com.shadowfit.service.notification;

import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.notification.NotificationRepository;
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
 */
@Component
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository notificationRepository;

    @Transactional
    public Notification insert(Member sender, Member recipient, NotificationType type, LocalDate targetDate) {
        return notificationRepository.saveAndFlush(Notification.builder()
                .sender(sender)
                .recipient(recipient)
                .type(type)
                .targetDate(targetDate)
                .build());
    }
}
