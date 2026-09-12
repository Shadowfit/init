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
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재촉하기 → 알림 행 → 목록(keyset)·미읽음·읽음 — social-cheer-and-group-feed.md §4-1 #6·§4-2.
 * 권한(같은 ACTIVE 그룹)·남발 방지(같은 날 1회)·keyset 경계를 실제 쿼리로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("알림·재촉하기 통합테스트")
class NotificationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member me, mate, left, outsider;

    @BeforeEach
    void setUp() {
        me = save("me"); mate = save("mate"); left = save("left"); outsider = save("outsider");
        Group group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("TESTCD01").createdBy(me).build());
        join(group, me, GroupMemberStatus.ACTIVE);
        join(group, mate, GroupMemberStatus.ACTIVE);
        join(group, left, GroupMemberStatus.LEFT);   // 나갔던 사람 — 같은 그룹이지만 ACTIVE 가 아니다
    }

    @Test
    @DisplayName("POST /members/{id}/nudge — 같은 모임 ACTIVE 멤버에게 201, 알림 행에 sender·targetDate(서버 오늘)")
    void nudge_createsNotification() throws Exception {
        mockMvc.perform(post("/members/" + mate.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("NUDGE"))
                .andExpect(jsonPath("$.sender.memberId").value(me.getId()))
                .andExpect(jsonPath("$.sender.username").value("me"))
                .andExpect(jsonPath("$.targetDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.readAt").value(nullValue()));

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRecipient().getId()).isEqualTo(mate.getId());
        assertThat(rows.get(0).getSender().getId()).isEqualTo(me.getId());
    }

    @Test
    @DisplayName("같은 사람에게 같은 날 두 번째는 409(N002)")
    void nudge_twiceSameDay_conflict() throws Exception {
        mockMvc.perform(post("/members/" + mate.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/members/" + mate.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.NUDGE_ALREADY_SENT_TODAY.getMessage()));
        // 반대 방향은 별개 — (sender, recipient) 쌍이 다르다
        mockMvc.perform(post("/members/" + me.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("권한 — 모임 밖 403, LEFT 멤버 403, 자기 자신 400, 없는 회원 404")
    void nudge_authorization() throws Exception {
        mockMvc.perform(post("/members/" + outsider.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_GROUP_MEMBER.getMessage()));
        mockMvc.perform(post("/members/" + left.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/members/" + me.getId() + "/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.NUDGE_SELF.getMessage()));
        mockMvc.perform(post("/members/999999/nudge")
                        .header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isNotFound());
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("GET /notifications — 최신순 keyset: size+1 로 hasNext 판정, nextCursor 로 다음 장이 이어지고 남의 알림은 안 보인다")
    void list_keysetPaging() throws Exception {
        // mate 가 받은 알림 5건(날짜를 달리해 UNIQUE 회피) + outsider 가 받은 1건
        for (int i = 0; i < 5; i++) {
            notificationRepository.saveAndFlush(Notification.builder().recipient(mate).sender(me)
                    .type(NotificationType.NUDGE).targetDate(LocalDate.of(2026, 9, 1).plusDays(i)).build());
        }
        notificationRepository.saveAndFlush(Notification.builder().recipient(outsider).sender(me)
                .type(NotificationType.NUDGE).targetDate(LocalDate.of(2026, 9, 1)).build());
        List<Long> ids = notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient().getId().equals(mate.getId())).map(Notification::getId).sorted().toList();

        // 첫 장: size=2 → 최신 2건(id 내림차순), hasNext, nextCursor = 두 번째 id
        mockMvc.perform(get("/notifications").param("size", "2")
                        .header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(4)))
                .andExpect(jsonPath("$.items[1].id").value(ids.get(3)))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value(ids.get(3)));

        // 다음 장: before=nextCursor → 그 아래 2건
        mockMvc.perform(get("/notifications").param("size", "2").param("before", String.valueOf(ids.get(3)))
                        .header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$.items[1].id").value(ids.get(1)))
                .andExpect(jsonPath("$.hasNext").value(true));

        // 마지막 장: 1건 남음 → hasNext=false, nextCursor=null
        mockMvc.perform(get("/notifications").param("size", "2").param("before", String.valueOf(ids.get(1)))
                        .header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        // 딱 맞아떨어지는 경계 — size=5 면 5건에 hasNext=false (6번째가 없으므로)
        mockMvc.perform(get("/notifications").param("size", "5")
                        .header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("읽음 — 건별 PATCH 는 readAt 을 채우고 멱등, read-all 은 남은 미읽음만 세어 돌려주며 unread-count 가 0 이 된다")
    void read_andReadAll() throws Exception {
        Notification n1 = notificationRepository.saveAndFlush(Notification.builder().recipient(mate).sender(me)
                .type(NotificationType.NUDGE).targetDate(LocalDate.of(2026, 9, 1)).build());
        notificationRepository.saveAndFlush(Notification.builder().recipient(mate).sender(me)
                .type(NotificationType.NUDGE).targetDate(LocalDate.of(2026, 9, 2)).build());
        notificationRepository.saveAndFlush(Notification.builder().recipient(mate).sender(me)
                .type(NotificationType.NUDGE).targetDate(LocalDate.of(2026, 9, 3)).build());

        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(jsonPath("$.unreadCount").value(3));

        mockMvc.perform(patch("/notifications/" + n1.getId() + "/read").header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").isNotEmpty());
        LocalDateTime firstReadAt = notificationRepository.findById(n1.getId()).orElseThrow().getReadAt();

        // 두 번째 호출 — 먼저 읽은 시각이 사실, 안 바뀐다
        mockMvc.perform(patch("/notifications/" + n1.getId() + "/read").header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(status().isOk());
        assertThat(notificationRepository.findById(n1.getId()).orElseThrow().getReadAt()).isEqualTo(firstReadAt);

        // 남의 알림은 404 — 존재 여부를 흘리지 않는다
        mockMvc.perform(patch("/notifications/" + n1.getId() + "/read").header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.NOTIFICATION_NOT_FOUND.getMessage()));

        mockMvc.perform(patch("/notifications/read-all").header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(2));
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(jsonPath("$.unreadCount").value(0));
        mockMvc.perform(patch("/notifications/read-all").header("Authorization", "Bearer " + tokenFor(mate)))
                .andExpect(jsonPath("$.updatedCount").value(0));
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@test.com").username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
    }

    private void join(Group g, Member m, GroupMemberStatus status) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(g).member(m).role(GroupRole.MEMBER).status(status).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
