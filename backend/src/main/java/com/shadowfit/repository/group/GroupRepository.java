package com.shadowfit.repository.group;

import com.shadowfit.model.group.Group;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    // GroupEventService.publish()의 시퀀스 채번용 — 이 그룹 행을 잠근 채로 next_seq를
    // 읽고 증가시켜야 동시 publish 사이의 경합을 막을 수 있다 (Group.allocateNextSeq() 참고).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Group g where g.id = :id")
    Optional<Group> findByIdForUpdate(@Param("id") Long id);

    // 초대 코드 발급 전 존재 확인 — 최종 방어선은 uk_workout_groups_invite_code (GroupService 참고).
    boolean existsByInviteCode(String inviteCode);

    // 코드 참여용 — 같은 그룹에 대한 가입(신규·되살리기)을 직렬화하려고 findByIdForUpdate 와
    // 같은 잠금으로 조회한다. 더블탭 두 요청이 둘 다 «행 없음»을 보고 INSERT 해 UNIQUE(group_id,
    // member_id) 위반 500 이 나는 것을, 두 번째가 줄 서다 ACTIVE 를 보고 409 로 끝나게 바꾼다.
    // 잠금은 publish() 가 seq 채번에 쓰는 것과 같은 행이라 새 경합 대상이 생기는 게 아니다
    // (GroupService.admit 참고).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Group g where g.inviteCode = :inviteCode")
    Optional<Group> findByInviteCodeForUpdate(@Param("inviteCode") String inviteCode);
}