package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모임 출석 캘린더를 실제 GROUP BY 쿼리로 — 같은 날 한 사람의 세션 여러 개는 1명, 완료 아닌 세션은 0,
 * LEFT 멤버의 출석은 분모·분자 어디에도 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("모임 출석 캘린더 통합테스트")
class GroupAttendanceCalendarIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // 고정 달을 쓴다 — LocalDate.now() 에 걸리지 않게(캘린더는 임의 연월을 받는다).
    private static final LocalDate D1 = LocalDate.of(2026, 7, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 9);

    private Member me, other, left, outsider;
    private Group group;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        me = save("me"); other = save("other"); left = save("left"); outsider = save("outsider");
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("TESTCD01").createdBy(me).build());
        join(group, me, GroupMemberStatus.ACTIVE);
        join(group, other, GroupMemberStatus.ACTIVE);
        join(group, left, GroupMemberStatus.LEFT);

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true).build());

        session(me, D1, 9, Status.COMPLETED);
        session(me, D1, 20, Status.COMPLETED);      // 같은 날 두 번 — 1명으로
        session(other, D1, 10, Status.COMPLETED);   // D1: 2명
        session(other, D2, 10, Status.FAILED);      // 실패는 출석 아님 — D2: 0명... 아래 left 도 무시
        session(left, D2, 10, Status.COMPLETED);    // LEFT 멤버 — 분자에 안 들어감
        session(outsider, D1, 10, Status.COMPLETED); // 모임 밖 — 무관
    }

    @Test
    @DisplayName("GET /groups/{id}/attendance — 31일 전부, D1=2·D2=0, 분모=ACTIVE 2 (LEFT 제외)")
    void attendanceCalendar_countsDistinctActiveCompletedOnly() throws Exception {
        mockMvc.perform(get("/groups/" + group.getId() + "/attendance")
                        .param("year", "2026").param("month", "7")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(7))
                .andExpect(jsonPath("$.activeMemberCount").value(2))
                .andExpect(jsonPath("$.days", hasSize(31)))
                .andExpect(jsonPath("$.days[0].date").value("2026-07-01"))
                .andExpect(jsonPath("$.days[2].date").value("2026-07-03"))
                .andExpect(jsonPath("$.days[2].attendedCount").value(2))
                .andExpect(jsonPath("$.days[8].attendedCount").value(0))
                .andExpect(jsonPath("$.days[30].attendedCount").value(0));
    }

    @Test
    @DisplayName("GET /groups/{id}/attendance — 모임 밖 403, month=13 은 400")
    void attendanceCalendar_forbiddenAndBadMonth() throws Exception {
        mockMvc.perform(get("/groups/" + group.getId() + "/attendance")
                        .param("year", "2026").param("month", "7")
                        .header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/groups/" + group.getId() + "/attendance")
                        .param("year", "2026").param("month", "13")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isBadRequest());
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@test.com").username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
    }

    private void join(Group g, Member m, GroupMemberStatus status) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(g).member(m).role(GroupRole.MEMBER).status(status).build());
    }

    private void session(Member m, LocalDate date, int hour, Status s) {
        sessionRepository.saveAndFlush(Session.builder().member(m).exercise(exercise)
                .startTime(date.atTime(hour, 0)).endTime(date.atTime(hour, 20)).status(s).totalReps(20).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
