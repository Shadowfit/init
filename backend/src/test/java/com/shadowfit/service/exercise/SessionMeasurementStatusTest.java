package com.shadowfit.service.exercise;

import com.shadowfit.dto.report.detailreport.ExerciseSessionDto;
import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.grpc.MeasurementStatus;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.Report;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완료 통보의 «측정 상태» (V37) — total_reps 가 0 인 이유를 세션에 남기고 목록·리포트로 내보낸다.
 *
 * <p>지키는 것: ① AI 가 보낸 사유가 저장되고 일일 목록·리포트 응답에 같은 이름으로 실린다 ② «측정됨»(OK)·모르는 값은 null
 * ③ <b>우리 기록과 어긋나면 사유를 남기지 않는다</b>(AI 는 0 이라는데 total_reps 가 양수) ④ 멱등 재전송이 첫 기록을 안 덮는다.
 */
@SpringBootTest
@Transactional
@DisplayName("세션 측정 상태(V37) 테스트")
class SessionMeasurementStatusTest {

    @Autowired private SessionCompletionTx sessionCompletionTx;
    @Autowired private SessionActivityQueryService activityQueryService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    // 발행기가 테스트 도중 돌면 검증이 흔들린다 (SessionCaloriesCompletionTest 와 같은 이유).
    @MockitoBean private OutboxPublisher outboxPublisher;

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("measure-status@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .analysisSupported(true).build());
    }

    private Session inProgress() {
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(5))
                .status(Status.IN_PROGRESS).build());
    }

    private void complete(Session session, int totalReps, MeasurementStatus status) {
        sessionCompletionTx.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId())
                .setTotalReps(totalReps)
                .setMeasurementStatus(status)
                .build());
        sessionRepository.flush();
    }

    private Session reload(Session session) {
        return sessionRepository.findById(session.getId()).orElseThrow();
    }

    @Test
    @DisplayName("NO_POSE — 0회로 끝난 세션에 사유가 저장되고, 목록·리포트 응답에 같은 이름으로 실린다")
    void noPose_isStoredAndExposed() {
        Session session = inProgress();

        complete(session, 0, MeasurementStatus.MEASUREMENT_NO_POSE);

        Session done = reload(session);
        assertThat(done.getMeasurementStatus()).isEqualTo(com.shadowfit.model.exercise.MeasurementStatus.NO_POSE);

        ExerciseSessionDto listed = activityQueryService.getDailyActivity(member.getId(), LocalDate.now())
                .getSessions().get(0);
        assertThat(listed.getMeasurementStatus()).isEqualTo("NO_POSE");
        assertThat(listed.isMeasured()).isFalse();

        SessionReportResponseDto report = SessionReportResponseDto.of(done, new Report());
        assertThat(report.getMeasurementStatus()).isEqualTo("NO_POSE");
    }

    @Test
    @DisplayName("NOT_FULLY_VISIBLE · NO_REP 도 각각 그 이름으로 저장된다")
    void otherReasons_areStored() {
        Session visible = inProgress();
        complete(visible, 0, MeasurementStatus.MEASUREMENT_NOT_FULLY_VISIBLE);
        assertThat(reload(visible).getMeasurementStatus())
                .isEqualTo(com.shadowfit.model.exercise.MeasurementStatus.NOT_FULLY_VISIBLE);

        // 같은 회원은 진행 중 세션을 둘 가질 수 없으므로 위 세션이 이미 COMPLETED 인 상태에서 새로 만든다.
        Session noRep = inProgress();
        complete(noRep, 0, MeasurementStatus.MEASUREMENT_NO_REP);
        assertThat(reload(noRep).getMeasurementStatus())
                .isEqualTo(com.shadowfit.model.exercise.MeasurementStatus.NO_REP);
    }

    @Test
    @DisplayName("OK(측정됨) 는 null 로 남는다 — 사유 없음이 정상이다")
    void ok_isNull() {
        Session session = inProgress();

        complete(session, 0, MeasurementStatus.MEASUREMENT_OK);

        assertThat(reload(session).getMeasurementStatus()).isNull();
    }

    @Test
    @DisplayName("이 필드를 모르는 구버전 AI(기본값 0=OK)가 완료시킨 세션도 null — «측정 불가» 로 오분류하지 않는다")
    void oldAiWithoutField_isNull() {
        Session session = inProgress();

        // measurement_status 를 아예 설정하지 않은 요청 — proto3 기본값 0 이 그대로 온다.
        sessionCompletionTx.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId()).setTotalReps(0).build());
        sessionRepository.flush();

        assertThat(reload(session).getMeasurementStatus()).isNull();
    }

    @Test
    @DisplayName("모르는 값(신버전 AI 의 새 사유)은 null — 억지로 어느 칸에 넣지 않는다")
    void unrecognizedValue_isNull() {
        Session session = inProgress();

        sessionCompletionTx.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId()).setTotalReps(0).setMeasurementStatusValue(99).build());
        sessionRepository.flush();

        assertThat(reload(session).getMeasurementStatus()).isNull();
    }

    @Test
    @DisplayName("AI 는 NO_POSE 라는데 total_reps 가 양수면 사유를 남기지 않는다 — 우리 기록과 어긋나면 측정된 세션이다")
    void conflictingWithRecordedReps_isNull() {
        Session session = inProgress();

        complete(session, 3, MeasurementStatus.MEASUREMENT_NO_POSE);

        Session done = reload(session);
        assertThat(done.getTotalReps()).isEqualTo(3);
        assertThat(done.getMeasurementStatus()).isNull();
    }

    @Test
    @DisplayName("멱등 재전송이 첫 완료의 사유를 덮어쓰지 않는다")
    void retransmission_doesNotOverwriteFirstStatus() {
        Session session = inProgress();
        complete(session, 0, MeasurementStatus.MEASUREMENT_NO_POSE);

        // 같은 완료가 다른 사유로 다시 온다(응답 유실 뒤 재전송 등) — 이미 COMPLETED 라 전이하지 않고 끝난다.
        complete(session, 0, MeasurementStatus.MEASUREMENT_NO_REP);

        assertThat(reload(session).getMeasurementStatus())
                .isEqualTo(com.shadowfit.model.exercise.MeasurementStatus.NO_POSE);
    }

    @Test
    @DisplayName("측정된 세션의 응답에는 사유가 null 이다")
    void measuredSession_hasNoReason() {
        Session session = inProgress();
        complete(session, 0, MeasurementStatus.MEASUREMENT_OK);

        ExerciseSessionDto listed = activityQueryService.getDailyActivity(member.getId(), LocalDate.now())
                .getSessions().get(0);

        assertThat(listed.getMeasurementStatus()).isNull();
    }
}
