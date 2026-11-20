package com.shadowfit.model.group;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 피드 글 아래 «내 응원 한마디»(V27). 한 글에 회원당 한 줄 — 다시 보내면 {@link #changeMessage} 로 바뀐다.
 */
@Entity
@Table(name = "event_cheers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "member_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"event", "member"})
public class EventCheer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private GroupEvent event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Column(nullable = false, length = 100)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void changeMessage(String message) {
        this.message = message;
    }
}
