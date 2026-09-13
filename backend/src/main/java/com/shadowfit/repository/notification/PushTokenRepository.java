package com.shadowfit.repository.notification;

import com.shadowfit.model.notification.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    // 등록 upsert 의 조회 키 — UNIQUE(token).
    Optional<PushToken> findByToken(String token);

    // #9 발행기가 «이 수신자의 기기들» 을 읽는 자리 — idx_push_tokens_member.
    List<PushToken> findAllByMemberId(Long memberId);

    // #9 — Expo 가 DeviceNotRegistered 로 답한 토큰을 지운다.
    void deleteByToken(String token);

    // 로그아웃 — refresh token 과 같이 계정 단위로 전부(§4-2 ②).
    void deleteByMemberId(Long memberId);
}
