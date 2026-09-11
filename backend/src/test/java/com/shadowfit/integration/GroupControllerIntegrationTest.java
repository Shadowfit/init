package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.group.CreateGroupRequestDto;
import com.shadowfit.dto.group.JoinGroupRequestDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.group.InvitationStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupInvitationRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GroupController 통합테스트 — 실제 보안 필터체인까지 태워서 HTTP 레벨로 검증한다
 * ({@code MemberControllerIntegrationTest}와 같은 방식). WebSocket 핸드셰이크 자체는
 * {@code JwtHandshakeInterceptorTest}에서 별도로 다루고, 여기서는 REST 쪽(그룹 CRUD·
 * 재연결 백필)만 다룬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("GroupController 통합테스트")
class GroupControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupMemberRepository groupMemberRepository;
    @Autowired private GroupInvitationRepository groupInvitationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member owner;
    private Member outsider;
    private String ownerToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        owner = memberRepository.saveAndFlush(newMember("owner@test.com", "owner"));
        outsider = memberRepository.saveAndFlush(newMember("outsider@test.com", "outsider"));
        ownerToken = tokenFor(owner);
        outsiderToken = tokenFor(outsider);
    }

    @Test
    @DisplayName("그룹 생성 — 201, 생성자가 OWNER로 가입된다")
    void createGroup_returns201AndJoinsCreatorAsOwner() throws Exception {
        mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGroupRequestDto("헬스 메이트"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("헬스 메이트"))
                .andExpect(jsonPath("$.createdById").value(owner.getId()));

        // 응답만이 아니라 실제로 ACTIVE·OWNER 멤버십이 생겼는지 DB로 확인한다.
        Group saved = groupRepository.findAll().stream()
                .filter(g -> g.getName().equals("헬스 메이트")).findFirst().orElseThrow();
        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(saved.getId(), owner.getId())
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(membership.getRole()).isEqualTo(GroupRole.OWNER);
        org.assertj.core.api.Assertions.assertThat(membership.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("그룹 생성 — 인증 없이 호출하면 401")
    void createGroup_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGroupRequestDto("헬스 메이트"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("그룹 생성 — 이름이 비어있으면 400")
    void createGroup_blankName_returns400() throws Exception {
        mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGroupRequestDto(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("내 그룹 목록 — ACTIVE 멤버인 그룹만 반환한다")
    void listMyGroups_returnsOnlyActiveMemberships() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(get("/groups/mine").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(group.getId()));

        mockMvc.perform(get("/groups/mine").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("그룹 상세 조회 — 멤버는 200, 멤버 아니면 403")
    void getGroupDetail_memberVsNonMember() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(get("/groups/" + group.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].memberId").value(owner.getId()));

        mockMvc.perform(get("/groups/" + group.getId()).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("그룹 상세 조회 — 존재하지 않는 그룹이면 404")
    void getGroupDetail_unknownGroup_returns404() throws Exception {
        mockMvc.perform(get("/groups/999999").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("그룹 탈퇴 — 200, 이후 상세 조회는 403")
    void leaveGroup_thenDetailForbidden() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(delete("/groups/" + group.getId() + "/members/me")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/groups/" + group.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("재연결 백필 — afterSeq 이후 이벤트가 없으면 빈 배열, 멤버 아니면 403")
    void getEventsAfter_emptyWhenNoNewEvents_forbiddenForNonMember() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(get("/groups/" + group.getId() + "/events")
                        .param("afterSeq", "0")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/groups/" + group.getId() + "/events")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("그룹 생성 — description 과 8자리 초대 코드가 응답에 실리고 DB 에도 저장된다")
    void createGroup_issuesInviteCodeAndStoresDescription() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateGroupRequestDto("거북목 탈출", "우리 진짜 거북목 되지 말자"));
        String json = mockMvc.perform(post("/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("우리 진짜 거북목 되지 말자"))
                .andExpect(jsonPath("$.inviteCode").isString())
                .andReturn().getResponse().getContentAsString();

        String code = objectMapper.readTree(json).get("inviteCode").asText();
        assertThat(code).hasSize(8).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}");
        assertThat(groupRepository.existsByInviteCode(code)).isTrue();
    }

    @Test
    @DisplayName("초대 코드 재발급 — OWNER 는 200 과 새 코드, 이전 코드는 더 이상 존재하지 않는다")
    void regenerateInviteCode_ownerGetsFreshCode() throws Exception {
        Group group = createGroupWithOwner();

        String json = mockMvc.perform(post("/groups/" + group.getId() + "/invite-code")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").isString())
                .andReturn().getResponse().getContentAsString();

        String fresh = objectMapper.readTree(json).get("inviteCode").asText();
        assertThat(fresh).hasSize(8).isNotEqualTo("TESTCD01");
        groupRepository.flush();
        assertThat(groupRepository.existsByInviteCode("TESTCD01")).isFalse();
        assertThat(groupRepository.existsByInviteCode(fresh)).isTrue();
    }

    @Test
    @DisplayName("초대 코드 재발급 — 일반 멤버는 403 NOT_GROUP_OWNER, 비멤버는 403 NOT_GROUP_MEMBER")
    void regenerateInviteCode_nonOwnerForbidden() throws Exception {
        Group group = createGroupWithOwner();
        Member plain = memberRepository.saveAndFlush(newMember("plain@test.com", "plain"));
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(plain).role(GroupRole.MEMBER).status(GroupMemberStatus.ACTIVE).build());

        mockMvc.perform(post("/groups/" + group.getId() + "/invite-code")
                        .header("Authorization", "Bearer " + tokenFor(plain)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_GROUP_OWNER.getMessage()));

        mockMvc.perform(post("/groups/" + group.getId() + "/invite-code")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_GROUP_MEMBER.getMessage()));

        // 실패한 요청은 코드를 건드리지 않는다.
        assertThat(groupRepository.existsByInviteCode("TESTCD01")).isTrue();
    }

    @Test
    @DisplayName("코드 참여 — 201, 승인 없이 바로 ACTIVE·MEMBER, MEMBER_JOINED 가 그룹 이벤트에 남는다")
    void joinByInviteCode_joinsImmediately() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(post("/groups/join")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinGroupRequestDto("TESTCD01"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(group.getId()))
                .andExpect(jsonPath("$.inviteCode").value("TESTCD01"));

        GroupMember membership = groupMemberRepository.findByGroupIdAndMemberId(group.getId(), outsider.getId()).orElseThrow();
        assertThat(membership.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(membership.getRole()).isEqualTo(GroupRole.MEMBER);

        mockMvc.perform(get("/groups/" + group.getId() + "/events")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MEMBER_JOINED"));
    }

    @Test
    @DisplayName("코드 참여 — 소문자·공백으로 쳐도 들어간다")
    void joinByInviteCode_normalizesInput() throws Exception {
        Group group = createGroupWithOwner();

        mockMvc.perform(post("/groups/join")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinGroupRequestDto(" testcd01 "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(group.getId()));
    }

    @Test
    @DisplayName("코드 참여 — 없는 코드는 404 INVALID_INVITE_CODE, 이미 멤버면 409 ALREADY_GROUP_MEMBER")
    void joinByInviteCode_unknownOrAlreadyMember() throws Exception {
        createGroupWithOwner();

        mockMvc.perform(post("/groups/join")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinGroupRequestDto("ZZZZZZZZ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INVITE_CODE.getMessage()));

        // OWNER 는 이미 ACTIVE 다 — 더블탭 두 번째 요청이 보는 것과 같은 결말.
        mockMvc.perform(post("/groups/join")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinGroupRequestDto("TESTCD01"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.ALREADY_GROUP_MEMBER.getMessage()));
    }

    @Test
    @DisplayName("코드 참여 — 탈퇴(LEFT)했던 사람은 새 행 없이 되살아난다 (UNIQUE 재삽입 500 회귀 방지)")
    void joinByInviteCode_leftMemberRejoins() throws Exception {
        Group group = createGroupWithOwner();
        GroupMember left = groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(outsider).role(GroupRole.MEMBER).status(GroupMemberStatus.LEFT).build());

        mockMvc.perform(post("/groups/join")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinGroupRequestDto("TESTCD01"))))
                .andExpect(status().isCreated());

        groupMemberRepository.flush();
        GroupMember revived = groupMemberRepository.findByGroupIdAndMemberId(group.getId(), outsider.getId()).orElseThrow();
        assertThat(revived.getId()).isEqualTo(left.getId());
        assertThat(revived.getStatus()).isEqualTo(GroupMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("코드 참여 — 같은 그룹의 PENDING 초대가 있으면 ACCEPTED 로 닫힌다")
    void joinByInviteCode_closesPendingInvitation() throws Exception {
        Group group = createGroupWithOwner();
        GroupInvitation pending = groupInvitationRepository.saveAndFlush(GroupInvitation.builder()
                .group(group).inviter(owner).invitee(outsider).build());

        mockMvc.perform(post("/groups/join")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinGroupRequestDto("TESTCD01"))))
                .andExpect(status().isCreated());

        groupInvitationRepository.flush();
        assertThat(groupInvitationRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.ACCEPTED);
    }

    private Group createGroupWithOwner() {
        Group group = groupRepository.saveAndFlush(Group.builder().name("그룹").inviteCode("TESTCD01").createdBy(owner).build());
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .group(group).member(owner).role(GroupRole.OWNER).status(GroupMemberStatus.ACTIVE).build());
        return group;
    }

    private Member newMember(String email, String username) {
        return Member.builder().email(email).username(username)
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build();
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
