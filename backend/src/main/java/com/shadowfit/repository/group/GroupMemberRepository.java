package com.shadowfit.repository.group;

import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    // WebSocket 핸드셰이크 인가·그룹 조회 인가 — "이 사용자가 이 그룹의 ACTIVE 멤버인가".
    boolean existsByGroupIdAndMemberIdAndStatus(Long groupId, Long memberId, GroupMemberStatus status);

    // OWNER 탈퇴 판정용 — «나 말고 ACTIVE 가 있는가». 있으면 양도 먼저(409), 없으면 모임 삭제(#721).
    boolean existsByGroupIdAndStatusAndMemberIdNot(Long groupId, GroupMemberStatus status, Long memberId);

    Optional<GroupMember> findByGroupIdAndMemberId(Long groupId, Long memberId);

    // GET /groups/{groupId} 의 멤버 목록.
    List<GroupMember> findAllByGroupIdAndStatus(Long groupId, GroupMemberStatus status);

    // GET /groups/mine.
    List<GroupMember> findAllByMemberIdAndStatus(Long memberId, GroupMemberStatus status);

    // 세션 완료 시 «자동 글을 받을 모임이 하나라도 있나» — 없으면 아웃박스 행을 안 만든다
    // (social-cheer-and-group-feed.md §4-4 ②). FK 의 암묵 인덱스 (member_id) 를 탄다.
    boolean existsByMemberIdAndStatus(Long memberId, GroupMemberStatus status);

    // 자동 글 팬아웃 — 그룹 id 오름차순으로 잠가야 하므로 정렬해서 받는다(§4-4 ③ b 잠금 순서 규약).
    List<GroupMember> findAllByMemberIdAndStatusOrderByGroupIdAsc(Long memberId, GroupMemberStatus status);

    // 구성원 현황·친구 현황용 — 회원(닉네임·프로필)까지 한 번에. member 가 LAZY 라 fetch join 이
    // 없으면 멤버 수만큼 N+1 이 난다.
    @Query("select gm from GroupMember gm join fetch gm.member "
         + "where gm.group.id = :groupId and gm.status = :status")
    List<GroupMember> findAllWithMemberByGroupIdAndStatus(@Param("groupId") Long groupId,
                                                          @Param("status") GroupMemberStatus status);

    @Query("select gm from GroupMember gm join fetch gm.member "
         + "where gm.group.id in :groupIds and gm.status = :status")
    List<GroupMember> findAllWithMemberByGroupIdInAndStatus(@Param("groupIds") Collection<Long> groupIds,
                                                            @Param("status") GroupMemberStatus status);

    // 재촉 권한 — «두 회원이 같은 그룹에 둘 다 ACTIVE 인가»(social-cheer-and-group-feed.md §3-G).
    // 친구 = 같은 모임 멤버(§3-A b)라 이 한 조인이 곧 «친구인가» 다. 자기 자신은 항상 참이므로
    // 호출자가 먼저 걸러야 한다.
    @Query("select count(a) > 0 from GroupMember a join GroupMember b on a.group = b.group "
         + "where a.member.id = :memberId and b.member.id = :otherId "
         + "and a.status = :status and b.status = :status")
    boolean shareGroupWithStatus(@Param("memberId") Long memberId, @Param("otherId") Long otherId,
                                 @Param("status") GroupMemberStatus status);
}