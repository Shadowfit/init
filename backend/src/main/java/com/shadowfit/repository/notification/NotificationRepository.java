package com.shadowfit.repository.notification;

import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 남발 방지 사전 검사 — UNIQUE(uk_notifications_daily)와 같은 네 컬럼. 경합의 두 번째는 UNIQUE 가 잡는다.
    boolean existsBySenderIdAndRecipientIdAndTypeAndTargetDate(Long senderId, Long recipientId,
                                                               NotificationType type, LocalDate targetDate);

    /**
     * 받은 알림 최신순 keyset — {@code before} 가 null 이면 첫 페이지. 정렬 키는 id 하나다: AUTO_INCREMENT
     * 라 created_at 순서와 같고, (recipient_id, id) 인덱스를 그대로 타서 offset 처럼 앞 페이지를
     * 다시 세지 않는다. sender 는 응답에 닉네임·프로필이 실리므로 fetch join(N+1 방지).
     */
    @Query("select n from Notification n left join fetch n.sender "
         + "where n.recipient.id = :recipientId and (:before is null or n.id < :before) "
         + "order by n.id desc")
    List<Notification> findPageByRecipient(@Param("recipientId") Long recipientId,
                                           @Param("before") Long before,
                                           Pageable pageable);

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    // read-all — 한 문장. 영속성 컨텍스트에 이미 올라온 엔티티가 있으면 낡은 readAt 을 들고 있으므로 비운다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.recipient.id = :recipientId and n.readAt is null")
    int markAllRead(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);
}
