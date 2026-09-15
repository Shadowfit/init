package com.shadowfit.model.group;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 그룹 내 실시간 이벤트의 append-only 로그 한 건. {@code seq}가 그룹 안에서
 * 유일·오름차순임을 {@code UNIQUE(group_id, seq)}로 DB가 보장한다 — WebSocket
 * pub/sub은 fire-and-forget이라 끊긴 동안의 이벤트가 유실되므로, 재연결 시
 * 백필(afterSeq 조회)의 유일한 근거가 이 테이블이다.
 */
@Entity
@Table(name = "group_events",
        // 자동 글 재발행 멱등성의 최종 방어선(V19, social-cheer-and-group-feed.md §4-4 ④ c). NULL 은 여러 개
        // 허용되므로 source_id 가 없는 타입엔 아무 제약도 안 건다.
        uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "event_type", "source_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"group", "sender"})
public class GroupEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Group group;

    @Column(nullable = false)
    private Long seq;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    // 시스템이 발행하는 이벤트(예: MEMBER_JOINED)는 특정 발신자가 없어 null 을 허용한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Member sender;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * 이 글의 원천 id — {@code SESSION_COMPLETED} 면 {@code exercise_sessions.id}. 아웃박스가 같은 통보를
     * 두 번 배달해도(at-least-once) 같은 글이 두 번 생기지 않게 하는 키다. 원천이 없는 시스템·클라이언트
     * 이벤트는 {@code null}. FK 를 걸지 않는다 — 세션이 지워져도 글은 남는다.
     */
    @Column(name = "source_id")
    private Long sourceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}