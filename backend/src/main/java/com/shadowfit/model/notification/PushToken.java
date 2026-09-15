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

import java.time.LocalDateTime;

/**
 * 회원의 푸시 디바이스 토큰 한 개 — 회원당 기기 여러 개(social-cheer-and-group-feed.md §3-C c 하위 ①, §4-2).
 *
 * <p><b>토큰이 전역 유일</b>이다(UNIQUE(token)) — 한 기기의 토큰은 항상 마지막으로 등록한 계정 것이어야
 * 공용 기기에서 남의 재촉이 안 간다. 그래서 등록은 «토큰으로 찾아 소유자를 옮기는» upsert 다
 * ({@link #reassign}).
 *
 * <p>UNIQUE·INDEX 를 애노테이션으로도 적는 이유는 {@code Notification} 과 같다 — 테스트 스키마는 V18 이
 * 아니라 이 엔티티에서 나온다.
 */
@Entity
@Table(name = "push_tokens",
       uniqueConstraints = @UniqueConstraint(name = "uk_push_tokens_token", columnNames = "token"),
       indexes = @Index(name = "idx_push_tokens_member", columnList = "member_id"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(exclude = "member")
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Column(nullable = false, length = 255)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PushPlatform platform;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    // 재등록마다 갱신 — 기록용이지 만료 판정 근거가 아니다(§4-2 ③). @UpdateTimestamp 를 안 쓰는 이유:
    // 같은 계정·같은 플랫폼의 재등록은 dirty 가 아니라 갱신이 안 찍힌다 — «마지막으로 등록한 때» 가 기록이므로 직접 쓴다.
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 같은 토큰의 재등록 — 소유자·플랫폼을 최신 등록으로 맞추고 등록 시각을 찍는다. 같은 계정이어도 시각은 갱신. */
    public void reassign(Member member, PushPlatform platform, LocalDateTime now) {
        this.member = member;
        this.platform = platform;
        this.updatedAt = now;
    }
}
