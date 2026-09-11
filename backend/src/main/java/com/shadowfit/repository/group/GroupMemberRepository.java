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

    Optional<GroupMember> findByGroupIdAndMemberId(Long groupId, Long memberId);

    // GET /groups/{groupId} 의 멤버 목록.
    List<GroupMember> findAllByGroupIdAndStatus(Long groupId, GroupMemberStatus status);

    // GET /groups/mine.
    List<GroupMember> findAllByMemberIdAndStatus(Long memberId, GroupMemberStatus status);

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
}