package com.shadowfit.model.group;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 피드 글({@link GroupEvent}) 하나에 회원 하나가 누른 리액션 한 종류 — social-cheer-and-group-feed.md §4-5.
 * {@code UNIQUE(event_id, member_id, kind)} 라 같은 글에 💗🔥 둘 다 가능하고, 같은 종류는 하나다. 카운트는
 * 행을 세는 것이지 컬럼이 아니다(§3-D — 12명 규모에 denormalize 근거 없음).
 */
@Entity
@Table(name = "event_reactions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "member_id", "kind"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"event", "member"})
public class EventReaction {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReactionKind kind;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
