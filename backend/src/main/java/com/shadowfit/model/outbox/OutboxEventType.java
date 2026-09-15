package com.shadowfit.model.outbox;

/**
 * 아웃박스가 나르는 통보 종류.
 *
 * <p>String 이 아니라 enum 인 이유: 이 값은 <b>발행기의 분기 대상</b>이다(타입마다 보낼 gRPC 호출이
 * 다르다). 오타가 나면 그 행은 아무도 처리하지 못한 채 재시도만 반복하다 FAILED 로 떨어지는데,
 * 컴파일 시점에 잡히지 않으면 운영에서야 발견된다. 닫힌 집합이므로 enum 이 맞다.
 *
 * <p>반면 {@code aggregate_type} 은 String 으로 둔다 — 분기에 쓰이지 않고 조회·디버깅용 라벨이라
 * 닫아둘 이유가 없다.
 */
public enum OutboxEventType {

    /**
     * 세션 종료 → AI 에 분석 중단 통보(gRPC {@code StopAnalysis}).
     * payload: {@code { "sessionId": 42 }}
     */
    STOP_ANALYSIS,

    /**
     * AI 워커 서킷브레이커 OPEN → 그 워커로 라우팅되던 IN_PROGRESS 세션의 상태 복구
     * (gRPC {@code ReattachAnalysis}). 컨테이너가 재기동돼 채널이 다시 살아나면 발행기가
     * 자동으로 재시도한다(docs/decisions/ai-channel-pool-hardening.md §3-1 ㄴ).
     * payload: {@code { "sessionId": 42 }}
     */
    REATTACH_ANALYSIS,

    /**
     * 알림 행 생성 → 수신자의 기기로 푸시(Expo Push HTTP). 아웃박스의 <b>두 번째 용처</b>다 —
     * 상대가 AI 가 아니라 외부 푸시 서비스이고, 애그리거트는 세션이 아니라 알림이다
     * (docs/decisions/social-cheer-and-group-feed.md §3-C c, §4-3).
     * payload: {@code { "notificationId": 42 }}
     */
    PUSH_NOTIFICATION,

    /**
     * 세션 완료 → 회원이 속한 ACTIVE 그룹마다 {@code group_events} 에 자동 글 하나씩. <b>세 번째 용처</b> —
     * 상대가 바깥이 아니라 <b>같은 DB 의 다른 애그리거트</b>(그룹)다. 같은 트랜잭션에 직접 INSERT 하지
     * 않는 이유는 애그리거트 경계(완료 tx 가 그룹 N행 락을 리포트 계산까지 쥐게 된다)이고, 재발행 멱등성은
     * {@code group_events.source_id} UNIQUE 가 맡는다(social-cheer-and-group-feed.md §4-4).
     * payload: {@code { "sessionId": 42 }}
     */
    SESSION_COMPLETED,

    /**
     * 주간 리포트 행({@code weekly_reports}, PENDING) → Gemini 로 문장 생성 → 행을 LLM/TEMPLATE_FALLBACK 으로.
     * <b>네 번째 용처이자 별도 차선</b> — 앞의 넷과 달리 호출이 초 단위(실측 loadtest/results/gemini-latency-2026-09-14)
     * 라 같은 tick 에 섞이면 다른 타입의 lease 를 잡아먹는다. {@code WeeklyReportOutboxPublisher} 만 집는다
     * (report-generation-llm.md §5-2 안 A, §14).
     * payload: {@code { "weeklyReportId": 42 }}
     */
    GENERATE_WEEKLY_REPORT
}
