package com.shadowfit.repository.member;

import com.shadowfit.model.member.ConsentType;
import com.shadowfit.model.member.MemberConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MemberConsentRepository extends JpaRepository<MemberConsent, Long> {

    boolean existsByMemberIdAndConsentTypeAndVersion(Long memberId, ConsentType consentType, String version);

    /** 회원의 동의 이력 전부 — 개정 전 버전도 포함한다(언제 어떤 버전에 동의했는지가 기록의 목적이다). */
    List<MemberConsent> findAllByMemberIdOrderByAgreedAtAsc(Long memberId);
}
