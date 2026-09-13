package com.shadowfit.service.notification;

import com.shadowfit.dto.notification.PushTokenRegisterRequestDto;
import com.shadowfit.dto.notification.PushTokenResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 푸시 토큰 등록 — 토큰 기준 upsert(social-cheer-and-group-feed.md §4-2 ①).
 *
 * <p>같은 토큰이 이미 있으면 누구 것이든 소유자를 요청자로 옮긴다 — 한 기기의 토큰은 항상 마지막으로
 * 등록한 계정 것이다. 없으면 INSERT. 두 요청이 같은 토큰을 동시에 처음 등록하면 둘째의 INSERT 가
 * UNIQUE 에서 걸리는데, 그때는 재조회해서 갱신으로 이어간다 — 결과는 어느 쪽이 이겨도 같다(멱등).
 * 걸음마다 트랜잭션을 끊는 이유는 {@link PushTokenStore}.
 *
 * <p>삭제는 여기 없다 — 로그아웃({@code MemberService.logout}, 계정 단위)과 #9 의 DeviceNotRegistered
 * 처리가 그 자리다(§4-2 ②).
 */
@Service
@RequiredArgsConstructor
public class PushTokenService {

    private final PushTokenStore store;

    public PushTokenResponseDto register(Long memberId, PushTokenRegisterRequestDto request, LocalDateTime now) {
        return store.reassignIfExists(request.token(), memberId, request.platform(), now)
                .map(PushTokenResponseDto::from)
                .orElseGet(() -> insertOrReassign(memberId, request, now));
    }

    private PushTokenResponseDto insertOrReassign(Long memberId, PushTokenRegisterRequestDto request, LocalDateTime now) {
        try {
            return PushTokenResponseDto.from(store.insert(request.token(), memberId, request.platform()));
        } catch (DataIntegrityViolationException e) {
            // 동시 첫 등록 경합 — 상대가 먼저 넣었다. 그 행을 이어받는다. 재조회도 비어 있으면 상대가 그새
            // 지운 것(로그아웃)이라 원래 예외를 그대로 — 이 창은 실제로 열리기 어렵고, 열려도 다음 앱 실행이 재등록한다.
            return store.reassignIfExists(request.token(), memberId, request.platform(), now)
                    .map(PushTokenResponseDto::from)
                    .orElseThrow(() -> e);
        }
    }
}
