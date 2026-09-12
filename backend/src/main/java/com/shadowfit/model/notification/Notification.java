package com.shadowfit.model.notification;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 알림 한 건 — 재촉하기의 저장 원천(social-cheer-and-group-feed.md §3-C c). 소켓·푸시는 이 행을
 * «전달» 하는 수단이지 원천이 아니다.
 *
 * <p>{@code sender} 는 null 일 수 있다 — 보낸 사람이 탈퇴하면 FK 가 SET NULL 로 남긴다(알림은 수신자의
 * 기록이라 보낸 사람이 사라져도 남는다, {@code group_events.sender_id} 와 같은 판단).
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(exclude = {"sender", "recipient"})
// V17 의 uk_notifications_sender_recipient_type_date 와 짝 — 테스트(H2, ddl-auto: create-drop)는 이
// 애노테이션으로 스키마를 만들므로, 여기 없으면 테스트만 제약 없이 초록불이 뜬다(goals 와 같은 이유).
@Table(name = "notifications",
        uniqueConstraints = @UniqueConstraint(columnNames = {"sender_id", "recipient_id", "type", "target_date"}))
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Member sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    /** 하루 1회 판정 단위 — 서버 LocalDate. 출석의 날짜 귀속과 같은 기준. */
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

    /** 읽음 처리. 이미 읽었으면 처음 읽은 시각을 유지한다 — 두 번 눌러도 같은 답. */
    public void markRead(LocalDateTime at) {
        if (readAt == null) {
            readAt = at;
        }
    }
}
