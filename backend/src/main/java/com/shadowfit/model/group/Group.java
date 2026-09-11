package com.shadowfit.model.group;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 다중사용자 실시간 동기화(그룹/파트너)의 그룹 하나. 테이블명이 {@code GROUP}이 아니라
 * {@code workout_groups}인 것은 {@code GROUP}이 SQL 예약어라서다.
 *
 * <p>{@code nextSeq}는 {@code group_events.seq}를 원자적으로 채번하기 위한 카운터다.
 * {@code GroupEventService}가 이 엔티티를 비관적 쓰기 잠금(row lock)으로 조회한 뒤
 * {@link #allocateNextSeq()}를 호출해 값을 받아간다 — 단일 인스턴스 전제이며, 다중
 * 인스턴스로 갈 때는 Redis {@code INCR} 등으로 대체될 자리다
 * ({@code docs/decisions/multiuser-realtime-sync.md} §7 세션3 이후).
 */
@Entity
@Table(name = "workout_groups")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"createdBy"})
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 모임 소개 한 줄 (선택). 레퍼런스 화면의 그룹명 아래 줄. */
    @Column(length = 255)
    private String description;

    /**
     * 코드 참여용 초대 코드 — 그룹당 1개 고정, 8자리 (V15, social-cheer-and-group-feed.md §3-F).
     * 발급은 {@code InviteCodeGenerator}, 유일성은 {@code uk_workout_groups_invite_code} 가
     * 최종 방어선. 유출 시 {@link #regenerateInviteCode(String)} 로 OWNER 가 갈아끼운다.
     */
    @Column(name = "invite_code", nullable = false, unique = true, length = 8)
    private String inviteCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member createdBy;

    @Column(name = "next_seq", nullable = false)
    @Builder.Default
    private Long nextSeq = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    /**
     * 다음 이벤트 시퀀스 번호를 발급한다. 호출자가 이 엔티티를 비관적 쓰기 잠금으로 조회한
     * 트랜잭션 안에서만 불러야 원자성이 보장된다.
     */
    public long allocateNextSeq() {
        this.nextSeq += 1;
        return this.nextSeq;
    }

    /** 초대 코드를 새 값으로 바꾼다. 이전 코드로는 더 이상 참여할 수 없다. */
    public void regenerateInviteCode(String newCode) {
        this.inviteCode = newCode;
    }
}