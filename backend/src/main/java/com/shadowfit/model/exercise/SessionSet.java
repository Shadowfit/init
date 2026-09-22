package com.shadowfit.model.exercise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 세션의 세트 하나 — 완료 시점에 {@code pose_data} 의 rep 별 집계에서 만들어진다 (V26,
 * {@code SessionSetAssembler}). AI 가 보내는 값이 아니다.
 *
 * <p>세트 경계는 «rep 이 {@code Session.targetRepsPerSet} 에 닿는 순간» 하나뿐이라, 이 행은 «rep 몇 번부터
 * 몇 번까지» 를 따로 들지 않는다 — set_no 와 세션의 목표값으로 역산된다. 중간 세트의 {@code reps} 는 항상
 * 목표와 같고 마지막 세트만 미달일 수 있다.
 *
 * <p>PK (session_id, set_no). 대리키를 안 두는 이유 — 이 표를 세션 밖에서 참조하는 곳이 없고, 세션 안에서
 * 세트 번호가 곧 정체다.
 */
@Entity
@Table(name = "exercise_session_sets")
@IdClass(SessionSetId.class)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SessionSet {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @Id
    @Column(name = "set_no", nullable = false)
    private Integer setNo;

    @Column(nullable = false)
    private Integer reps;

    /** rep 가중 평균 — 싱크 통계(#75)와 같은 계산. */
    @Column(name = "avg_sync_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal avgSyncRate;

    /** 세트 첫 rep 의 첫 프레임 {@code timestamp_sec} (pose_data 와 같은 원점). */
    @Column(name = "started_sec", nullable = false)
    private Double startedSec;

    /** 세트 마지막 rep 의 마지막 프레임 {@code timestamp_sec}. */
    @Column(name = "ended_sec", nullable = false)
    private Double endedSec;
}
