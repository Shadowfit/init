package com.shadowfit.service.exercise;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 출석 계산 설정 — {@code application.yml} 의 {@code attendance} 블록. 이 클래스는 받기만 한다.
 */
@Component
@ConfigurationProperties(prefix = "attendance")
@Getter
@Setter
public class AttendanceProperties {

    /** 여러 회원의 streak 를 어떻게 읽나. */
    public enum StreakStrategy {
        /** 회원마다 커서 쿼리 1회(현재 구현, 후보 a). */
        PER_MEMBER,
        /** LATERAL 한 방 + 31일 초과 회원만 단건 이어 걷기(실험용 후보 b). MySQL 8.0.14+ 전용. */
        BATCH
    }

    /**
     * 기본 {@link StreakStrategy#PER_MEMBER}. {@code BATCH} 는 friend-status-streak-fanout-experiment-design.md
     * 의 비교 후보라 실험 rig 만 켠다 — 채택은 그 실측 뒤 별도 결정.
     */
    private StreakStrategy streakStrategy = StreakStrategy.PER_MEMBER;
}
