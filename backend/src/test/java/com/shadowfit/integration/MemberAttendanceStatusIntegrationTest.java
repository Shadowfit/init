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
 * 구성원 현황·친구 현황을 HTTP 로 — 권한(같은 ACTIVE 모임)·정렬·노출 항목(오늘 여부·연속일수뿐)을 실제
 * 쿼리로 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("구성원/친구 운동 현황 통합테스트")
class MemberAttendanceStatusIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final LocalDate today = LocalDate.now();
    private Member me, done, alive, none, outsider;
    private Group group;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        me = save("me");
        done = save("zed");
        alive = save("amy");
        none = save("bob");
        outsider = save("outsider");
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("TESTCD01").createdBy(me).build());
        join(group, me, GroupRole.OWNER);
        join(group, done, GroupRole.MEMBER);
        join(group, alive, GroupRole.MEMBER);
        join(group, none, GroupRole.MEMBER);

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true).build());

        session(done, today, Status.COMPLETED);
        session(done, today.minusDays(1), Status.COMPLETED);
        session(alive, today.minusDays(1), Status.COMPLETED);
        session(alive, today.minusDays(2), Status.COMPLETED);
        session(alive, today.minusDays(3), Status.COMPLETED);
        session(none, today, Status.FAILED); // 실패는 출석이 아니다
    }

    @Test
    @DisplayName("GET /groups/{id}/members/status — 완료(zed) → 진행 중(amy, 3일) → 없음(bob) → 나(me, 없음) 순; 건강 지표 없음")
    void groupMemberStatuses_orderAndFields() throws Exception {
        mockMvc.perform(get("/groups/" + group.getId() + "/members/status")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].username").value("zed"))
                .andExpect(jsonPath("$[0].attendedToday").value(true))
                .andExpect(jsonPath("$[0].streak").value(2))
                .andExpect(jsonPath("$[1].username").value("amy"))
                .andExpect(jsonPath("$[1].attendedToday").value(false))
                .andExpect(jsonPath("$[1].streak").value(3))
                .andExpect(jsonPath("$[2].username").value("bob"))
                .andExpect(jsonPath("$[2].streak").value(0))
                .andExpect(jsonPath("$[3].username").value("me"))
                .andExpect(jsonPath("$[0].avgSyncRate").doesNotExist())
                .andExpect(jsonPath("$[0].totalReps").doesNotExist());
    }

    @Test
    @DisplayName("GET /groups/{id}/members/status — 모임 밖 사람은 403")
    void groupMemberStatuses_outsiderForbidden() throws Exception {
        mockMvc.perform(get("/groups/" + group.getId() + "/members/status")
                        .header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /friends — 내 모임 사람들(나 제외), 두 모임에 겹친 사람은 한 번, 같은 정렬")
    void friendStatuses_unionAcrossGroups() throws Exception {
        Group second = groupRepository.saveAndFlush(Group.builder().name("둘째").inviteCode("TESTCD02").createdBy(me).build());
        join(second, me, GroupRole.OWNER);
        join(second, alive, GroupRole.MEMBER); // 두 모임에 겹침
        join(second, outsider, GroupRole.MEMBER); // 둘째 모임에서만 친구

        mockMvc.perform(get("/friends")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].username").value("zed"))
                .andExpect(jsonPath("$[1].username").value("amy"))
                .andExpect(jsonPath("$[2].username").value("bob"))
                .andExpect(jsonPath("$[3].username").value("outsider"))
                .andExpect(jsonPath("$[?(@.username == 'me')]").doesNotExist());
    }

    @Test
    @DisplayName("GET /friends — 모임이 없으면 빈 목록")
    void friendStatuses_noGroups() throws Exception {
        mockMvc.perform(get("/friends")
                        .header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@test.com").username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
    }

    private void join(Group g, Member m, GroupRole role) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(g).member(m).role(role).status(GroupMemberStatus.ACTIVE).build());
    }

    private void session(Member m, LocalDate date, Status s) {
        sessionRepository.saveAndFlush(Session.builder().member(m).exercise(exercise)
                .startTime(date.atTime(10, 0)).endTime(date.atTime(10, 20)).status(s).totalReps(20).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
