package com.shadowfit.service.notification.push;

import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.model.outbox.DispatchOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 아웃박스 {@code PUSH_NOTIFICATION} 행 하나를 실제로 보내고 결과를 {@link DispatchOutcome} 으로 접는다
 * (social-cheer-and-group-feed.md §4-3 ①·③·④·⑦·⑨).
 *
 * <p><b>행 = 알림 1건</b>이고 수신자 토큰은 여기서 읽어 한 요청에 묶는다(①a). 그래서 티켓 분류는
 * 「행 전체의 다음 상태」로 접혀야 한다:
 * <ul>
 *   <li>전송 실패({@link ExpoPushTransportException}) → RETRY</li>
 *   <li>요청 거절({@link ExpoPushRejectedException}) → TERMINAL_FAILED</li>
 *   <li>티켓 {@code DeviceNotRegistered} → 그 토큰 삭제. 나머지 기준으로 판정하되, 전부가 이거면
 *       아무 데도 안 간 것이라 TERMINAL_FAILED</li>
 *   <li>티켓 {@code MessageRateExceeded} 가 하나라도 → RETRY (Expo 문서: 지수 백오프로 재시도).
 *       ok 였던 토큰에 중복이 갈 수 있다 — ①a 의 대가, at-least-once 그대로</li>
 *   <li>그 외 티켓 오류(MessageTooBig·MismatchSenderId·InvalidCredentials·미지) → TERMINAL_FAILED + ERROR 로그</li>
 *   <li>전부 ok(또는 ok + DeviceNotRegistered 만) → SENT</li>
 * </ul>
 *
 * <p>토큰이 0개면 TERMINAL_FAILED 다(④ c). 적재 시점에 기기가 있던 수신자만 행이 만들어지므로, 여기서
 * 0개는 「그새 로그아웃했다」는 뜻이고 실제로 못 보낸 것이 맞다.
 *
 * <p>회수분(이전 발행기가 송신 «도중» 죽은 행)은 이미 한 번 폰에 갔을 수 있지만 구분할 방법이 없어
 * 그대로 보낸다(⑨) — 두 번 울리는 쪽이 안 울리는 쪽보다 낫다는 제품 판단.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushDispatchService {

    static final String TITLE = "ShadowFit";

    private final PushDispatchStore store;
    private final ExpoPushClient client;

    public DispatchOutcome dispatch(Long notificationId) {
        PushTarget target = store.load(notificationId).orElse(null);
        if (target == null) {
            // 적재 뒤 수신자 탈퇴(CASCADE) — 보낼 대상이 사라졌다. 재시도해도 같다.
            log.warn("푸시 대상 알림이 없음 — notificationId: {}", notificationId);
            return DispatchOutcome.TERMINAL_FAILED;
        }
        if (target.tokens().isEmpty()) {
            log.warn("푸시 대상 기기가 없음(적재 뒤 로그아웃) — notificationId: {}", notificationId);
            return DispatchOutcome.TERMINAL_FAILED;
        }

        List<ExpoPushMessage> messages = target.tokens().stream()
                .map(token -> ExpoPushMessage.of(token, TITLE, body(target), data(target)))
                .toList();

        List<ExpoPushTicket> tickets;
        try {
            tickets = client.send(messages);
        } catch (ExpoPushTransportException e) {
            log.warn("Expo 전송 실패 — 재시도 대상 (notificationId: {}): {}", notificationId, e.getMessage());
            return DispatchOutcome.RETRY;
        } catch (ExpoPushRejectedException e) {
            log.error("Expo 가 요청을 거절 — 재시도 무의미 (notificationId: {}): {}", notificationId, e.getMessage());
            return DispatchOutcome.TERMINAL_FAILED;
        }
        return classify(target, messages, tickets);
    }

    private DispatchOutcome classify(PushTarget target, List<ExpoPushMessage> messages, List<ExpoPushTicket> tickets) {
        List<String> dead = new ArrayList<>();
        boolean rateExceeded = false;
        boolean terminal = false;
        for (int i = 0; i < tickets.size(); i++) {
            ExpoPushTicket ticket = tickets.get(i);
            if (ticket.ok()) {
                continue;
            }
            String token = messages.get(i).to();
            switch (ticket.errorCode()) {
                case ExpoPushTicket.DEVICE_NOT_REGISTERED -> dead.add(token);
                case ExpoPushTicket.MESSAGE_RATE_EXCEEDED -> rateExceeded = true;
                default -> {
                    terminal = true;
                    log.error("Expo 티켓 오류 — 재시도 무의미 (notificationId: {}, error: {}, message: {})",
                            target.notificationId(), ticket.errorCode(), ticket.message());
                }
            }
        }
        if (!dead.isEmpty()) {
            store.forgetDeadTokens(dead);
            log.info("죽은 푸시 토큰 {}건 삭제 (notificationId: {})", dead.size(), target.notificationId());
        }
        if (terminal) {
            return DispatchOutcome.TERMINAL_FAILED;
        }
        if (rateExceeded) {
            return DispatchOutcome.RETRY;
        }
        if (dead.size() == tickets.size()) {
            // 기기가 전부 죽어 있었다 — Expo 는 받았지만 어디에도 안 간다. SENT 로 찍으면 거짓이다.
            log.warn("푸시 대상 기기가 전부 등록 해제됨 (notificationId: {})", target.notificationId());
            return DispatchOutcome.TERMINAL_FAILED;
        }
        return DispatchOutcome.SENT;
    }

    /** 문구(⑦). 알림 종류가 늘면 여기서 갈린다 — 지금은 NUDGE 하나라 switch 가 한 갈래다. */
    static String body(PushTarget target) {
        String who = target.senderName() == null ? "모임 친구가" : target.senderName() + "님이";
        return switch (target.type()) {
            case NUDGE -> who + " 오늘 운동을 재촉했어요";
        };
    }

    private static Map<String, Object> data(PushTarget target) {
        return Map.of("notificationId", target.notificationId(), "type", target.type().name());
    }
}
