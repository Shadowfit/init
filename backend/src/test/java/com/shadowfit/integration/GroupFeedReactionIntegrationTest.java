package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.EventReactionRepository;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.group.GroupEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모임 피드 + 리액션 — social-cheer-and-group-feed.md §4-5 를 HTTP 로 통째로. 리액션 쓰기가 REQUIRES_NEW 라
 * {@code @Transactional} 테스트로는 픽스처가 안 보이므로 직접 지운다({@code NudgePushOutboxIntegrationTest} 와 같은
 * 이유). 이벤트는 {@link GroupEventService#publish} 로 만든다 — seq 채번을 실제 경로로.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:group_feed_reaction;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1"
})
@DisplayName("모임 피드·리액션 통합테스트 (§4-5)")
class GroupFeedReactionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private GroupEventRepository groupEventRepository;
    @MockitoSpyBean private EventReactionRepository eventReactionRepository;
    @Autowired private GroupEventService groupEventService;

    private Member me, friend, outsider;
    private Group group;

    @BeforeEach
    void setUp() {
        me = save("feed-me");
        friend = save("feed-friend");
        outsider = save("feed-outsider");
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("FEEDRX01").createdBy(me).build());
        join(me);
        join(friend);
    }

    @AfterEach
    void tearDown() {
        eventReactionRepository.deleteAll();
        groupEventRepository.deleteAll(groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(group.getId(), 0L));
        groupMemberRepository.findAllByGroupIdAndStatus(group.getId(), GroupMemberStatus.ACTIVE).forEach(groupMemberRepository::delete);
        groupRepository.delete(group);
        memberRepository.deleteAll(List.of(me, friend, outsider));
    }

    @Test
    @DisplayName("피드는 최신순 keyset — size 만큼 차면 nextBeforeSeq, 다음 페이지는 그 미만, 끝나면 null")
    void feed_newestFirst_keyset() throws Exception {
        for (int i = 1; i <= 5; i++) {
            publish(me, "{\"n\":" + i + "}");
        }

        String page1 = feed(me, null, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].seq").value(5))
                .andExpect(jsonPath("$.items[1].seq").value(4))
                .andExpect(jsonPath("$.items[0].type").value(GroupEventTypes.SESSION_COMPLETED))
                .andExpect(jsonPath("$.items[0].senderId").value(me.getId()))
                .andExpect(jsonPath("$.items[0].reactionSummary.reactions.HEART").value(0))
                .andExpect(jsonPath("$.items[0].reactionSummary.reactions.FIRE").value(0))
                .andExpect(jsonPath("$.items[0].reactionSummary.myReactions", hasSize(0)))
                .andExpect(jsonPath("$.nextBeforeSeq").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat(page1).contains("\"payload\":\"{\\\"n\\\":5}\"");

        feed(me, 4L, 2)
                .andExpect(jsonPath("$.items[0].seq").value(3))
                .andExpect(jsonPath("$.items[1].seq").value(2))
                .andExpect(jsonPath("$.nextBeforeSeq").value(2));

        feed(me, 2L, 2)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].seq").value(1))
                .andExpect(jsonPath("$.nextBeforeSeq", nullValue()));
    }

    @Test
    @DisplayName("PUT 은 멱등 — 두 번 눌러도 1개, HEART·FIRE 둘 다 가능, 응답에 갱신된 카운트·내 리액션. DELETE 도 멱등")
    void react_idempotent_bothKinds_thenUnreact() throws Exception {
        long seq = publish(me, "{}").getSeq();

        react(me, seq, "HEART")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.HEART").value(1))
                .andExpect(jsonPath("$.reactions.FIRE").value(0))
                .andExpect(jsonPath("$.myReactions[0]").value("HEART"));
        react(me, seq, "HEART")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.HEART").value(1));
        react(me, seq, "FIRE")
                .andExpect(jsonPath("$.reactions.FIRE").value(1))
                .andExpect(jsonPath("$.myReactions", hasSize(2)));
        react(friend, seq, "HEART")
                .andExpect(jsonPath("$.reactions.HEART").value(2))
                .andExpect(jsonPath("$.myReactions", hasSize(1)))
                .andExpect(jsonPath("$.myReactions[0]").value("HEART"));
        assertThat(eventReactionRepository.count()).isEqualTo(3);

        // 피드에도 같은 요약이 실린다 — 요청자 기준 myReactions.
        feed(friend, null, 10)
                .andExpect(jsonPath("$.items[0].reactionSummary.reactions.HEART").value(2))
                .andExpect(jsonPath("$.items[0].reactionSummary.reactions.FIRE").value(1))
                .andExpect(jsonPath("$.items[0].reactionSummary.myReactions", hasSize(1)));

        unreact(me, seq, "HEART")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.HEART").value(1))
                .andExpect(jsonPath("$.myReactions[0]").value("FIRE"));
        unreact(me, seq, "HEART")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.HEART").value(1));
        assertThat(eventReactionRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("더블탭 — exists 가 놓친 틈의 두 번째 INSERT 는 UNIQUE 에 걸리고, 그건 «이미 있다» 라 200 + 1개")
    void doubleTap_uniqueViolation_isSuccess() throws Exception {
        long seq = publish(me, "{}").getSeq();
        // exists 를 항상 false 로 — «검사 직후 상대 요청이 먼저 넣은» 상태를 DB 가 보는 그대로 만든다
        // (SignupUsernameRaceTest 와 같은 방식: 타이밍이 아니라 창을 넓혀 순서를 고정).
        doReturn(false).when(eventReactionRepository).existsByEventIdAndMemberIdAndKind(anyLong(), anyLong(), any());

        react(me, seq, "HEART").andExpect(status().isOk()).andExpect(jsonPath("$.reactions.HEART").value(1));
        react(me, seq, "HEART").andExpect(status().isOk()).andExpect(jsonPath("$.reactions.HEART").value(1));

        assertThat(eventReactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("권한·대상 — 그룹 밖은 403, 없는 seq 는 404, 종류 밖은 400. MEMBER_JOINED 같은 다른 타입에도 달 수 있다(⑤)")
    void guards_andAnyType() throws Exception {
        long seq = publish(me, "{}").getSeq();

        react(outsider, seq, "HEART").andExpect(status().isForbidden());
        feed(outsider, null, 10).andExpect(status().isForbidden());
        react(me, 999L, "HEART").andExpect(status().isNotFound());
        react(me, seq, "THUMBS").andExpect(status().isBadRequest());

        long joinedSeq = groupEventService.publish(group.getId(), null, GroupEventTypes.MEMBER_JOINED, "{}").getSeq();
        react(friend, joinedSeq, "FIRE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.FIRE").value(1));
    }

    // ---------------------------------------------------------------------

    private GroupEvent publish(Member sender, String payload) {
        return groupEventService.publish(group.getId(), sender.getId(), GroupEventTypes.SESSION_COMPLETED, payload);
    }

    private ResultActions feed(Member who, Long beforeSeq, int size) throws Exception {
        var req = get("/groups/" + group.getId() + "/feed").param("size", String.valueOf(size))
                .header("Authorization", "Bearer " + tokenFor(who));
        if (beforeSeq != null) {
            req = req.param("beforeSeq", String.valueOf(beforeSeq));
        }
        return mockMvc.perform(req);
    }

    private ResultActions react(Member who, long seq, String kind) throws Exception {
        return mockMvc.perform(put("/groups/" + group.getId() + "/events/" + seq + "/reactions/" + kind)
                .header("Authorization", "Bearer " + tokenFor(who)));
    }

    private ResultActions unreact(Member who, long seq, String kind) throws Exception {
        return mockMvc.perform(delete("/groups/" + group.getId() + "/events/" + seq + "/reactions/" + kind)
                .header("Authorization", "Bearer " + tokenFor(who)));
    }

    private Member save(String username) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@test.com").username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
    }

    private void join(Member m) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(m).role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
