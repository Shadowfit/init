package com.shadowfit.service.exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LungeSessionTest {
    @Autowired ExerciseAnalysisService analysis;
    @Autowired PoseDataService poses;
    @Autowired SessionCompletionTx completion;
    @Autowired ReattachRequestBuilder reattach;
    @Autowired MemberRepository members;
    @Autowired ExercisesRepository exercises;
    @Autowired CategoryRepository categories;
    @Autowired SessionRepository sessions;
    @Autowired PoseDataRepository poseRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionActivityQueryService activities;
    @MockitoBean OutboxPublisher publisher;

    @Test
    void countOnlySessionStartsWithoutReferenceAndRestoresRepsWithoutScores() {
        Member member = members.saveAndFlush(Member.builder()
                .email("lunge-test@example.com").username("lunge").password("unused")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categories.saveAndFlush(Category.builder().name("LOWER").build());
        Exercise exercise = exercises.saveAndFlush(Exercise.builder()
                .name("전방 런지").category(category).analysisSupported(true).repetitionOnly(true).build());

        // 회원의 기준 영상 URL이 없어도 횟수 측정 세션은 시작할 수 있다.
        var started = analysis.startAnalysis(
                VideoRequestDto.builder().exerciseId(exercise.getId()).build(), member.getId());
        Session session = sessions.findById(started.sessionId()).orElseThrow();
        assertThat(started.sessionNonce()).isNotBlank();
        assertThat(session.getStatus()).isEqualTo(Status.IN_PROGRESS);

        poses.savePoseDataBatch(session.getId(), List.of(PoseDataRequest.newBuilder()
                .setTimestampSec(1).setJointCoordinates("[]").setRepNumber(1)
                .setSmoothedKneeAngle(90).setSyncRate(0).build()));

        assertThat(jdbc.queryForObject("SELECT sync_rate FROM pose_data WHERE session_id = ?",
                Double.class, session.getId())).isNull();
        assertThat(poseRepository.findRepAverageSyncRates(session.getId(), session.getStartTime())).isEmpty();
        var restored = reattach.build(session.getId(), member.getId());
        assertThat(restored.getInitialRepCount()).isEqualTo(1);
        assertThat(restored.getReferencePosesCount()).isZero();

        completion.applyComplete(SessionCompleteRequest.newBuilder().setSessionId(session.getId())
                .setTotalReps(1).setAvgSyncRate(0).build());
        assertThat(session.getTotalReps()).isEqualTo(1);
        assertThat(session.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(session.getAvgSyncRate()).isNull();
        assertThat(session.getMaxSyncRate()).isNull();
        assertThat(session.getMinSyncRate()).isNull();
        assertThat(activities.getDailyActivity(member.getId(), session.getStartTime().toLocalDate())
                .getSessions()).singleElement().satisfies(detail -> {
                    assertThat(detail.isScoreAvailable()).isFalse();
                    assertThat(detail.getSetSummary()).contains("1회");
                });
        assertThat(com.shadowfit.dto.report.detailreport.SessionReportResponseDto
                .of(session, new com.shadowfit.model.report.Report()).isScoreAvailable()).isFalse();
    }
}
