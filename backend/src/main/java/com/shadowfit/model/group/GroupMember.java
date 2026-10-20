package com.shadowfit.model.group;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 그룹 하나에 대한 사용자 한 명의 가입 관계. {@code UNIQUE(group_id, member_id)}
 * (V12__add_group_tables.sql)로 중복 가입을 DB 레벨에서 막는다.
 */
@Entity
@Table(name = "group_members")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"group", "member"})
public class GroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupMemberStatus status;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false, nullable = false)
    private LocalDateTime joinedAt;

    public void leave() {
        this.status = GroupMemberStatus.LEFT;
    }

    /**
     * LEFT 상태였던 멤버가 초대를 다시 수락했을 때 — 새 행을 만들지 않고 기존 행을 되살린다.
     *
     * <p>role 을 MEMBER 로 되돌리는 건 의도다: OWNER 는 양도한 뒤에만 나갈 수 있으므로(#721,
     * {@code GroupService.leaveGroup}) LEFT 행의 role 이 OWNER 인 경우는 이제 생기지 않고,
     * 예전 데이터에 남아 있더라도 되살아나며 두 번째 OWNER 가 되면 안 된다.
     */
    public void rejoin() {
        this.status = GroupMemberStatus.ACTIVE;
        this.role = GroupRole.MEMBER;
    }

    /** 양도 — 받는 쪽. 호출자({@code GroupService.transferOwnership})가 ACTIVE 인지 확인한다. */
    public void promoteToOwner() {
        this.role = GroupRole.OWNER;
    }

    /** 양도 — 주는 쪽. */
    public void demoteToMember() {
        this.role = GroupRole.MEMBER;
    }
}