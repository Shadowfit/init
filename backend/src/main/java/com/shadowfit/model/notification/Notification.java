package com.shadowfit.model.notification;

import com.shadowfit.model.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 알림 한 건 — 재촉하기의 저장 원천(social-cheer-and-group-feed.md §3-C c). 소켓·푸시는 이 행을
 * 나르는 수단이고, 앱이 꺼진 동안 온 재촉은 이 행이 있어서 안 잃는다.
 *
 * <p>UNIQUE·INDEX 를 애노테이션으로도 적는 이유는 {@code Goal} 과 같다 — 테스트(H2, ddl-auto
 * create-drop)는 V17 이 아니라 이 엔티티에서 스키마를 만들므로, 여기 없으면 테스트 DB 만 제약이
 * 빠진 채로 초록불이 뜬다.
 */
@Entity
@Table(name = "notifications",
       uniqueConstraints = @UniqueConstraint(name = "uk_notifications_daily",
               columnNames = {"sender_id", "recipient_id", "type", "target_date"}),
       indexes = {
               @Index(name = "idx_notifications_recipient_id", columnList = "recipient_id, id"),
               @Index(name = "idx_notifications_recipient_read", columnList = "recipient_id, read_at")
       })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(exclude = {"recipient", "sender"})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member recipient;

    // 탈퇴하면 NULL — group_events.sender_id 와 같은 정책. 이미 보낸 재촉은 남는다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Member sender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    // 서버 LocalDate — AttendanceService 의 «오늘» 과 같은 시계. 클라이언트 값은 받지 않는다(§4-2 ③).
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    public boolean isRead() {
        return readAt != null;
    }

    /** 이미 읽은 건 그대로 둔다 — 먼저 읽은 시각이 사실이다. */
    public void markRead(LocalDateTime at) {
        if (readAt == null) {
            readAt = at;
        }
    }
}
