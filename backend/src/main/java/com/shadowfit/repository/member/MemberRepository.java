package com.shadowfit.repository.member;

import com.shadowfit.model.member.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member,Long> {
    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    // 세션 생성 시 같은 회원에 대한 동시 요청을 직렬화 — existsByMemberIdAndStatus 체크와
    // save() 사이의 TOCTOU 레이스(둘 다 커밋 전이라 서로의 상태를 못 봄)를 막기 위함
    // (2026-07-16, SessionService.createSession).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
