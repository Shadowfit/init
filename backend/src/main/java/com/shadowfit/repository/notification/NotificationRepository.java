package com.shadowfit.repository.notification;

import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 재촉 전 «오늘 이미 보냈나» — UNIQUE 와 같은 네 열. 이 확인과 INSERT 사이의 더블탭은 제약이 막는다.
    boolean existsBySenderIdAndRecipientIdAndTypeAndTargetDate(Long senderId, Long recipientId,
                                                               NotificationType type, LocalDate targetDate);

    // 알림함 — 수신자의 것을 최신순으로. sender 는 닉네임·프로필을 실어야 해서 같이 읽는다(N+1 방지).
    @EntityGraph(attributePaths = "sender")
    Page<Notification> findAllByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId, Pageable pageable);

    // 읽음 처리 — «내 것» 만. 남의 id 는 존재하지 않는 것과 같게(404) 다룬다.
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);
}
