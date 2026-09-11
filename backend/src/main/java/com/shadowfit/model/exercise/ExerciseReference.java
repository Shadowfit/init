package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import jakarta.persistence.Id;

@Entity
@Table(name = "exercise_references")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@ToString(exclude = "exercise") // 연관관계 제외 추가
public class ExerciseReference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false) // 필수값 설정
    private Exercise exercise;

    // columnDefinition 은 Flyway 스키마(V1)를 그대로 옮긴 것이다 — race 프로파일의 ddl-auto: validate 가
    // 자바 Double(FLOAT) 과 DECIMAL 을 동치로 안 봐서, 선언이 없으면 컨텍스트가 안 뜬다.
    @Column(name = "timestamp_sec", nullable = false, columnDefinition = "DECIMAL(10,3)")
    private Double timestampSec;

    @Column(name = "joint_coordinates", columnDefinition = "json", nullable = false)
    private String jointCoordinates;
}