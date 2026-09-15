package com.shadowfit.service.notification.push;

/**
 * Expo 가 <b>요청 자체를 거절했고 다시 보내도 같다</b> — 4xx(429 제외: 잘못된 요청·자격 증명), 규격에
 * 안 맞는 응답. 발행기는 {@code DispatchOutcome.TERMINAL_FAILED} 로 본다.
 *
 * <p>서킷브레이커 집계에서 <b>뺀다</b>({@code application.yml} 의 {@code ignoreExceptions}) —
 * AI 채널의 {@code isClientRejection} 과 같은 이유: 우리 요청이 틀린 것은 상대가 건강하다는 증거지
 * 장애 신호가 아니다. 같이 세면 설정 오류 하나가 서킷을 열어 정상 발송까지 막는다.
 */
public class ExpoPushRejectedException extends RuntimeException {

    public ExpoPushRejectedException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExpoPushRejectedException(String message) {
        super(message);
    }
}
