package com.shadowfit.service.exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionService 가 «지금» 을 {@link Clock} 에서 읽는지 고정 시각으로 확인한다 (#638).
 *
 * <p>이 클래스만 고정 시계를 쓴다 — 기존 {@code SessionServiceTest} 는 데이터를 {@code LocalDateTime.now()} 로 만들어
 * 시계를 바꾸면 흔들리므로 섞지 않는다. 시작 시각(createSession)과 종료 시각(endSession) 두 곳을 본다. 타임아웃 판정
 * (isTimedOutAt)도 같은 시계를 읽지만 이 테스트가 따로 단언하지는 않는다.
 */
@SpringBootTest
@Transactional
@Import(SessionServiceClockTest.FixedClockConfig.class)
@DisplayName("SessionService — 시계 주입 (#638)")
class SessionServiceClockTest {

    /** 2030-01-15 09:30:45.123 (Asia/Seoul). 지금과 확실히 다른 미래라, 시스템 시계를 읽으면 단언이 바로 깨진다. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FIXED = LocalDateTime.of(2030, 1, 15, 9, 30, 45, 123_000_000);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED.atZone(ZONE).toInstant(), ZONE);
        }
    }

    @Autowired private SessionService sessionService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;

    // 발행기·AI 호출이 테스트 도중 돌지 않게 한다 (SessionServiceTest 와 같은 이유).
    @MockitoBean private ExerciseAnalysisService analysisService;
    @MockitoBean private OutboxPublisher outboxPublisher;

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("session-clock@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("CLOCK").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15).analysisSupported(true).build());
    }

    @Test
    @DisplayName("createSession 의 시작 시각은 주입된 시계의 «지금» 이다 (초 이하는 잘린다)")
    void createSession_startTime_comesFromClock() {
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

        Session created = sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy");

        assertThat(created.getStartTime()).isEqualTo(FIXED.withNano(0));
    }

    @Test
    @DisplayName("endSession 의 종료 시각은 주입된 시계의 «지금» 이다")
    void endSession_endTime_comesFromClock() {
        Session session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(FIXED.minusMinutes(10))
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());

        sessionService.endSession(session.getId(), member.getId());

        LocalDateTime endTime = sessionRepository.findById(session.getId()).orElseThrow().getEndTime();
        assertThat(endTime).isNotNull();
        // DB 컬럼 정밀도에 따라 초 이하가 잘릴 수 있어 초 단위까지만 본다 — 시스템 시계였다면 2030 년이 나올 수 없다.
        assertThat(endTime.withNano(0)).isEqualTo(FIXED.withNano(0));
    }
}
