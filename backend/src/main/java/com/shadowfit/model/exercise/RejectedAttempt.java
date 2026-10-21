package com.shadowfit.model.exercise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * «미인정 시도» 하나 (V35, 2026-10-21) — 내려갔다 올라왔지만 AI 가 1회로 인정하지 않은 시도.
 *
 * <p>횟수·세트·목표 달성에는 안 들어가고 <b>점수·리포트에만</b> 들어간다: 세션의 avg/max/min 싱크로율
 * ({@code SessionCompletionTx.resolveSyncStats})과 리포트의 회차 추이·깊이·체간 점수. 그래야 «좋은 자세만 세니
 * 리포트는 무조건 좋게 나온다» 가 안 된다({@code docs/decisions/counted-reps-vs-scored-attempts.md}).
 *
 * <p>AI 가 시도 하나를 «가장 깊이 앉은 순간» 한 장으로 요약해 보낸다 — 프레임 묶음이 아니다. 실제 쓰기는
 * {@code RejectedAttemptService} 의 JdbcTemplate {@code ON DUPLICATE KEY UPDATE}(멱등)가 하고, 이 엔티티는 조회·스키마
 * 정합({@code race} 프로파일 validate)·H2 테스트 스키마용이다.
 *
 * <p>{@code uk_rejected_attempt} 를 여기에도 선언하는 이유는 {@link PoseData} 와 같다 — 테스트는 H2 + ddl-auto 라
 * Flyway 를 안 보므로, 엔티티에 없으면 멱등 테스트가 제약 없는 스키마 위에서 초록불을 낸다.
 */
@Entity
@Table(name = "exercise_rejected_attempts",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_rejected_attempt",
               columnNames = {"session_id", "attempt_no"}))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RejectedAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // V35 의 ON DELETE CASCADE 와 일치 — 세션 삭제·회원 탈퇴 시 함께 정리
    private Session session;

    /** 세션 안 미인정 시도 번호(1부터). 재전송 멱등키의 둘째 컬럼이다. */
    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RejectedAttemptReason reason;

    /** 인정 반복과 같은 식으로 매긴 싱크로율 0~100. columnDefinition 은 {@link PoseData#getSyncRate()} 와 같은 이유. */
    @Column(name = "sync_rate", nullable = false, columnDefinition = "DECIMAL(5,2)")
    private Double syncRate;

    /** 가장 깊이 앉은 순간의 무릎각({@code pose_data.smoothed_knee_angle} 과 같은 정의). null = 미상. */
    @Column(name = "min_knee_angle", columnDefinition = "DECIMAL(5,2)")
    private Double minKneeAngle;

    /** 그 순간의 체간 기울기(도). {@code pose_data.torso_tilt_deg} 처럼 0 도 유효값(완전 직립)이다. */
    @Column(name = "torso_tilt_deg")
    private Double torsoTiltDeg;

    /** 그 순간의 세션 경과 초 — {@code pose_data.timestamp_sec} 과 같은 원점이라 리포트가 두 표를 시간순으로 섞는다. */
    @Column(name = "timestamp_sec", nullable = false, columnDefinition = "DECIMAL(10,3)")
    private Double timestampSec;

    @Column(name = "joint_coordinates", columnDefinition = "json")
    private String jointCoordinates;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
