package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import com.shadowfit.service.notification.NotificationWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재촉 → 알림함 → 읽음 흐름을 HTTP 로. 일부러 클래스 {@code @Transactional} 을 안 건다 — 그래야
 * {@code NotificationWriter} 의 트랜잭션이 실제로 커밋되고, 두 번째 INSERT 가 H2 에 만들어진
 * UNIQUE({@code Notification} 의 {@code @UniqueConstraint})에 진짜로 부딪힌다. 대신 만든 행은
 * {@code @AfterEach} 가 FK 순서대로 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("재촉·알림 통합테스트")
class NudgeNotificationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationWriter notificationWriter;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member me, friend, left, outsider;
    private Group group;

    @BeforeEach
    void setUp() {
        me = save("nudge-me"); friend = save("nudge-friend"); left = save("nudge-left"); outsider = save("nudge-outsider");
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("NUDGE001").createdBy(me).build());
        join(me, GroupMemberStatus.ACTIVE);
        join(friend, GroupMemberStatus.ACTIVE);
        join(left, GroupMemberStatus.LEFT);
    }

    @AfterEach
    void tearDown() {
        List<Member> all = List.of(me, friend, left, outsider);
        for (Member m : all) {
            notificationRepository.findAllByRecipientIdOrderByCreatedAtDescIdDesc(m.getId(),
                    org.springframework.data.domain.Pageable.unpaged()).forEach(notificationRepository::delete);
        }
        groupMemberRepository.findAllByGroupIdAndStatus(group.getId(), GroupMemberStatus.ACTIVE).forEach(groupMemberRepository::delete);
        groupMemberRepository.findAllByGroupIdAndStatus(group.getId(), GroupMemberStatus.LEFT).forEach(groupMemberRepository::delete);
        groupRepository.delete(group);
        memberRepository.deleteAll(all);
    }

    @Test
    @DisplayName("POST /friends/{id}/nudge — 201, 두 번째는 409, 알림함엔 한 건, 읽음 처리 후 read=true")
    void nudge_then_inbox_then_read() throws Exception {
        MvcResult created = mockMvc.perform(post("/friends/" + friend.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("NUDGE"))
                .andExpect(jsonPath("$.senderId").value(me.getId()))
                .andExpect(jsonPath("$.senderUsername").value("nudge-me"))
                .andExpect(jsonPath("$.targetDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.read").value(false))
                .andExpect(jsonPath("$.readAt", nullValue()))
                .andReturn();
        long notificationId = com.jayway.jsonpath.JsonPath.parse(created.getResponse().getContentAsString())
                .read("$.id", Long.class);

        mockMvc.perform(post("/friends/" + friend.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.NUDGE_ALREADY_SENT_TODAY.getMessage()));

        // 보낸 사람의 알림함은 비어 있고, 받은 사람에겐 한 건
        mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + tokenFor(friend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(notificationId))
                .andExpect(jsonPath("$.content[0].senderUsername").value("nudge-me"))
                .andExpect(jsonPath("$.content[0].read").value(false));

        // 보낸 사람은 남의 알림을 읽음 처리할 수 없다 — 없는 것과 같게 404
        mockMvc.perform(patch("/notifications/" + notificationId + "/read")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.NOTIFICATION_NOT_FOUND.getMessage()));

        mockMvc.perform(patch("/notifications/" + notificationId + "/read")
                        .header("Authorization", "Bearer " + tokenFor(friend)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + tokenFor(friend)))
                .andExpect(jsonPath("$.content[0].read").value(true));
    }

    @Test
    @DisplayName("POST /friends/{id}/nudge — 모임 밖 403, LEFT 멤버 403, 자기 자신 400, 없는 회원 404")
    void nudge_guards() throws Exception {
        mockMvc.perform(post("/friends/" + friend.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_GROUP_MEMBER.getMessage()));
        mockMvc.perform(post("/friends/" + left.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/friends/" + me.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.NUDGE_SELF_NOT_ALLOWED.getMessage()));
        mockMvc.perform(post("/friends/999999/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isNotFound());
        for (Member m : List.of(me, friend, left)) {
            assertThat(notificationRepository.findAllByRecipientIdOrderByCreatedAtDescIdDesc(m.getId(),
                    org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isZero();
        }
    }

    @Test
    @DisplayName("UNIQUE(sender, recipient, type, target_date) — 존재 확인을 우회한 두 번째 INSERT 는 DB 가 막는다")
    void uniqueConstraint_isRealInTestSchema() {
        LocalDate day = LocalDate.of(2026, 9, 12);
        notificationWriter.insert(me, friend, NotificationType.NUDGE, day);

        assertThatThrownBy(() -> notificationWriter.insert(me, friend, NotificationType.NUDGE, day))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 다른 날·다른 상대는 막히지 않는다
        notificationWriter.insert(me, friend, NotificationType.NUDGE, day.plusDays(1));
        notificationWriter.insert(friend, me, NotificationType.NUDGE, day);
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@test.com").username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
    }

    private void join(Member m, GroupMemberStatus status) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(m).role(GroupRole.MEMBER).status(status).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
