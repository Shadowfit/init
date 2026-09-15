package com.shadowfit.service.notification.push;

import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.model.outbox.DispatchOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 티켓 → {@link DispatchOutcome} 분류표(social-cheer-and-group-feed.md §4-3 ③·④)를 1:1 로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PushDispatchService 분류")
class PushDispatchServiceTest {

    @Mock private PushDispatchStore store;
    @Mock private ExpoPushClient client;
    @InjectMocks private PushDispatchService service;

    private static final long NID = 7L;

    private static ExpoPushTicket ok() {
        return new ExpoPushTicket("ok", "id", null, null);
    }

    private static ExpoPushTicket error(String code) {
        return new ExpoPushTicket("error", null, "msg", new ExpoPushTicket.Details(code));
    }

    private void target(String... tokens) {
        when(store.load(NID)).thenReturn(Optional.of(
                new PushTarget(NID, NotificationType.NUDGE, "철수", List.of(tokens))));
    }

    @Test
    @DisplayName("전부 ok → SENT, 메시지는 토큰마다 하나·문구는 «{이름}님이 오늘 운동을 재촉했어요»")
    void allOk_sent() {
        target("ExponentPushToken[a]", "ExponentPushToken[b]");
        when(client.send(anyList())).thenReturn(List.of(ok(), ok()));

        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.SENT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpoPushMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).send(captor.capture());
        assertThat(captor.getValue()).extracting(ExpoPushMessage::to)
                .containsExactly("ExponentPushToken[a]", "ExponentPushToken[b]");
        assertThat(captor.getValue().get(0).body()).isEqualTo("철수님이 오늘 운동을 재촉했어요");
        assertThat(captor.getValue().get(0).data())
                .containsEntry("notificationId", NID)
                .containsEntry("type", "NUDGE");
        verify(store, never()).forgetDeadTokens(anyList());
    }

    @Test
    @DisplayName("보낸 사람이 탈퇴해 없으면 «모임 친구가 …»")
    void senderGone_fallbackName() {
        assertThat(PushDispatchService.body(new PushTarget(NID, NotificationType.NUDGE, null, List.of())))
                .isEqualTo("모임 친구가 오늘 운동을 재촉했어요");
    }

    @Test
    @DisplayName("DeviceNotRegistered 섞임 → 그 토큰만 삭제, 나머지 ok 면 SENT")
    void deadTokenAmongOk_sentAndForget() {
        target("ExponentPushToken[a]", "ExponentPushToken[dead]");
        when(client.send(anyList())).thenReturn(List.of(ok(), error(ExpoPushTicket.DEVICE_NOT_REGISTERED)));

        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.SENT);
        verify(store).forgetDeadTokens(List.of("ExponentPushToken[dead]"));
    }

    @Test
    @DisplayName("전부 DeviceNotRegistered → 토큰 삭제 + TERMINAL_FAILED (아무 데도 안 갔다)")
    void allDead_terminal() {
        target("ExponentPushToken[dead]");
        when(client.send(anyList())).thenReturn(List.of(error(ExpoPushTicket.DEVICE_NOT_REGISTERED)));

        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
        verify(store).forgetDeadTokens(List.of("ExponentPushToken[dead]"));
    }

    @Test
    @DisplayName("MessageRateExceeded 하나라도 → RETRY (행 전체 — ok 였던 토큰엔 중복 가능, ①a 의 대가)")
    void rateExceeded_retry() {
        target("ExponentPushToken[a]", "ExponentPushToken[b]");
        when(client.send(anyList())).thenReturn(List.of(ok(), error(ExpoPushTicket.MESSAGE_RATE_EXCEEDED)));

        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.RETRY);
    }

    @Test
    @DisplayName("그 외 티켓 오류(InvalidCredentials·미지) → TERMINAL_FAILED, RateExceeded 보다 우선")
    void otherTicketError_terminal() {
        target("ExponentPushToken[a]", "ExponentPushToken[b]", "ExponentPushToken[c]");
        when(client.send(anyList())).thenReturn(List.of(
                error("InvalidCredentials"), error(ExpoPushTicket.MESSAGE_RATE_EXCEEDED), error(null)));

        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
    }

    @Test
    @DisplayName("전송 실패 예외 → RETRY")
    void transportException_retry() {
        target("ExponentPushToken[a]");
        when(client.send(anyList())).thenThrow(new ExpoPushTransportException("down"));
        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.RETRY);
    }

    @Test
    @DisplayName("거절 예외 → TERMINAL_FAILED")
    void rejectedException_terminal() {
        target("ExponentPushToken[a]");
        when(client.send(anyList())).thenThrow(new ExpoPushRejectedException("bad"));
        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
    }

    @Test
    @DisplayName("토큰 0개(적재 뒤 로그아웃) → HTTP 없이 TERMINAL_FAILED")
    void noTokens_terminalWithoutHttp() {
        target();
        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
        verify(client, never()).send(any());
    }

    @Test
    @DisplayName("알림이 없음(수신자 탈퇴 CASCADE) → TERMINAL_FAILED")
    void notificationGone_terminal() {
        when(store.load(NID)).thenReturn(Optional.empty());
        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
        verify(client, never()).send(any());
    }

    @Test
    @DisplayName("토큰이 100개를 넘으면 100개씩 나눠 보내고 티켓을 순서대로 합친다")
    void moreThanMaxPerRequest_chunked() {
        int n = ExpoPushClient.MAX_MESSAGES_PER_REQUEST + 50;
        String[] tokens = java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "ExponentPushToken[" + i + "]").toArray(String[]::new);
        target(tokens);
        when(client.send(anyList())).thenAnswer(inv -> {
            List<ExpoPushMessage> chunk = inv.getArgument(0);
            // 마지막 토큰만 죽은 것으로 — 순서가 보존돼야 정확히 그 토큰이 지워진다
            return chunk.stream()
                    .map(m -> m.to().equals("ExponentPushToken[" + (n - 1) + "]")
                            ? error(ExpoPushTicket.DEVICE_NOT_REGISTERED) : ok())
                    .toList();
        });

        assertThat(service.dispatch(NID)).isEqualTo(DispatchOutcome.SENT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpoPushMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(ExpoPushClient.MAX_MESSAGES_PER_REQUEST);
        assertThat(captor.getAllValues().get(1)).hasSize(50);
        verify(store).forgetDeadTokens(List.of("ExponentPushToken[" + (n - 1) + "]"));
    }
}
