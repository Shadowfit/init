package com.shadowfit.model.exercise;

import com.shadowfit.model.member.SelectedPersona;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GeneratedColumn;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 운동별 자세 문제에 대해 사용자에게 안내할 피드백 메시지 템플릿.
 * persona NULL row 는 페르소나 row 없을 때의 fallback (분기 4-A + BE-13).
 *
 * <p>UNIQUE 는 {@code persona} 가 아니라 {@code persona_key}(= {@code COALESCE(persona, '')}) 에 건다 —
 * MySQL 은 UNIQUE 에서 NULL 을 서로 다른 값으로 보므로 fallback 행만 몇 개든 들어갔다(#715, V22).
 */
@Entity
@Table(name = "exercise_feedback_templates",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_exercise_feedback_persona_key",
               columnNames = {"exercise_id", "feedback_type", "persona_key"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExerciseFeedbackTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    private Exercise exercise;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 30)
    private FeedbackType feedbackType;

    /**
     * null 이면 모든 페르소나 공통 fallback.
     *
     * <p>{@code @JdbcTypeCode(VARCHAR)}: 운영 컬럼은 VARCHAR(10)(V1)인데 Hibernate 가 H2 테스트 스키마에는
     * enum 타입으로 만든다. 그러면 아래 생성 컬럼 {@code COALESCE(persona, '')} 의 결과도 enum 으로 추론돼
     * {@code ''} 가 «허용되지 않는 값» 이 되어 fallback 행 INSERT 자체가 H2 에서 깨진다.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "persona", length = 10)
    private SelectedPersona persona;

    /**
     * {@code persona} 의 NULL 을 빈 문자열로 투영한 DB 생성 컬럼 — UNIQUE 전용, 애플리케이션은 안 읽는다.
     * 여기 매핑하는 이유는 H2 테스트 스키마(ddl-auto)도 같은 제약을 갖게 하려는 것뿐이다.
     */
    @GeneratedColumn("COALESCE(persona, '')")
    @Column(name = "persona_key", length = 10, insertable = false, updatable = false)
    private String personaKey;

    @Column(nullable = false, length = 200)
    private String message;

    /** 동시에 여러 문제가 감지될 때 우선순위 (낮을수록 우선). */
    @Builder.Default
    @Column(nullable = false)
    private Integer priority = 100;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}