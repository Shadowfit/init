package com.shadowfit.service.notification;

import com.shadowfit.dto.notification.PushTokenRegisterRequestDto;
import com.shadowfit.dto.notification.PushTokenResponseDto;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.PushPlatform;
import com.shadowfit.model.notification.PushToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 통합테스트가 못 만드는 경합 — 조회 땐 없었는데 INSERT 가 UNIQUE 에서 걸리는 «동시 첫 등록».
 */
@DisplayName("PushTokenService 테스트")
class PushTokenServiceTest {

    private static final String TOKEN = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";

    @Mock private PushTokenStore store;

    private PushTokenService service;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 12, 23, 0);
    private final PushTokenRegisterRequestDto request = new PushTokenRegisterRequestDto(TOKEN, PushPlatform.IOS);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PushTokenService(store);
    }

    @Test
    @DisplayName("있으면 갱신만 — INSERT 시도 없음")
    void register_existing_reassignsOnly() {
        when(store.reassignIfExists(TOKEN, 1L, PushPlatform.IOS, now)).thenReturn(Optional.of(row(7L)));

        PushTokenResponseDto result = service.register(1L, request, now);

        assertThat(result.id()).isEqualTo(7L);
        verify(store, never()).insert(TOKEN, 1L, PushPlatform.IOS);
    }

    @Test
    @DisplayName("동시 첫 등록 — INSERT 가 UNIQUE 에서 걸리면 재조회해 상대가 넣은 행을 이어받는다")
    void register_raceOnInsert_reassignsRacedRow() {
        when(store.reassignIfExists(TOKEN, 1L, PushPlatform.IOS, now))
                .thenReturn(Optional.empty())          // 처음엔 없었다
                .thenReturn(Optional.of(row(9L)));     // INSERT 실패 뒤엔 상대 행이 있다
        when(store.insert(TOKEN, 1L, PushPlatform.IOS)).thenThrow(new DataIntegrityViolationException("uk_push_tokens_token"));

        PushTokenResponseDto result = service.register(1L, request, now);

        assertThat(result.id()).isEqualTo(9L);
        verify(store, times(2)).reassignIfExists(TOKEN, 1L, PushPlatform.IOS, now);
    }

    @Test
    @DisplayName("재조회도 비어 있으면(상대가 그새 지움) 원래 예외 그대로")
    void register_raceThenGone_rethrows() {
        when(store.reassignIfExists(TOKEN, 1L, PushPlatform.IOS, now)).thenReturn(Optional.empty());
        when(store.insert(TOKEN, 1L, PushPlatform.IOS)).thenThrow(new DataIntegrityViolationException("uk_push_tokens_token"));

        assertThatThrownBy(() -> service.register(1L, request, now)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private PushToken row(Long id) {
        return PushToken.builder().id(id).member(Member.builder().id(1L).build()).token(TOKEN).platform(PushPlatform.IOS).build();
    }
}
