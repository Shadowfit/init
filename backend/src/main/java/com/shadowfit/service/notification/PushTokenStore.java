package com.shadowfit.service.notification;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.PushPlatform;
import com.shadowfit.model.notification.PushToken;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.PushTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 푸시 토큰 upsert 의 두 걸음을 <b>각각 자기 트랜잭션</b>으로 — {@code OutboxEventStore} 와 같은 이유로 분리했다.
 *
 * <p>INSERT 가 UNIQUE(token) 에서 걸렸을 때 같은 트랜잭션 안에서 «재조회 → 갱신» 으로 이어갈 수 없다:
 * 레포지토리 프록시를 빠져나온 예외가 공유 트랜잭션을 rollback-only 로 표시하고, 실패한 flush 뒤의
 * 영속성 컨텍스트는 그 엔티티를 계속 들고 있어 다음 flush 에서 또 INSERT 를 시도한다. 그래서
 * 시도 하나 = 트랜잭션 하나로 끊고, 호출자({@link PushTokenService})가 순서를 잇는다.
 */
@Component
@RequiredArgsConstructor
public class PushTokenStore {

    private final PushTokenRepository pushTokenRepository;
    private final MemberRepository memberRepository;

    /** 토큰이 있으면 소유자·플랫폼·등록 시각을 요청자 것으로 옮기고 그 행을, 없으면 empty. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PushToken> reassignIfExists(String token, Long memberId, PushPlatform platform, LocalDateTime now) {
        Optional<PushToken> existing = pushTokenRepository.findByToken(token);
        existing.ifPresent(t -> t.reassign(member(memberId), platform, now));
        return existing;
    }

    /** 새 행 INSERT. UNIQUE 위반은 그대로 던진다 — 호출자가 «상대가 먼저 넣었다» 로 해석해 재조회한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PushToken insert(String token, Long memberId, PushPlatform platform) {
        return pushTokenRepository.saveAndFlush(PushToken.builder()
                .member(member(memberId)).token(token).platform(platform).build());
    }

    private Member member(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
