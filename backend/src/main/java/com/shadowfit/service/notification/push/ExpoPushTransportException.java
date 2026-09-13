package com.shadowfit.service.notification.push;

/**
 * Expo 에 <b>닿지 못했거나 요청 단위로 거절당한</b> 실패 — 연결·타임아웃·5xx·429·서킷 OPEN.
 * 발행기는 이걸 통째로 {@code DispatchOutcome.RETRY} 로 본다. 티켓 단위 오류(200 응답 안의
 * {@code status=error})는 이 예외가 아니라 {@link ExpoPushTicket} 으로 돌아온다.
 */
public class ExpoPushTransportException extends RuntimeException {

    public ExpoPushTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExpoPushTransportException(String message) {
        super(message);
    }
}
