package com.shadowfit.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.notification.NotificationDto;
import com.shadowfit.service.group.GroupSocketRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 저장된 알림을 «접속 중인 수신자» 에게 WebSocket 으로 즉시 밀어주는 전달 수단
 * (social-cheer-and-group-feed.md §3-C c 의 ①, §4-1 #7).
 *
 * <p><b>저장이 원천, 이건 보너스.</b> 여기서 실패해도 요청은 성공이다 — 알림 행은 이미 있고
 * 알림함 조회·푸시(#9)가 남아 있다. 그래서 직렬화 실패도 로그만 남기고 삼킨다.
 *
 * <p><b>어느 연결로 가나</b>: 새 연결 종류를 만들지 않고 기존 그룹 WebSocket 을 재사용한다
 * (2026-09-12 confirm, {@link GroupSocketRegistry} 클래스 주석 «회원 인덱스»). 수신자가 어느
 * 모임 화면이든 보고 있으면 그 세션들로만 가고, 홈 화면·앱만 켜둔 상태면 아무 데도 안 간다.
 *
 * <p><b>프레임</b>: {@code {"type":"NOTIFICATION","notification":{…NotificationDto}}}. 그룹 이벤트
 * 프레임({@code seq}·{@code groupId} 가 있는 {@code GroupEventResponseDto})과 봉투가 다르다 —
 * 재촉엔 순번도 그룹도 없어서 같은 봉투에 넣으면 빈 칸만 생긴다. 클라이언트는 {@code type}
 * 하나로 가른다. 재연결 백필({@code group_events.seq})은 이 프레임을 모른다 — 끊긴 동안 온
 * 재촉은 알림함(`GET /notifications`)에 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRelay {

    public static final String FRAME_TYPE = "NOTIFICATION";

    private final GroupSocketRegistry groupSocketRegistry;
    private final ObjectMapper objectMapper;

    public void relay(Long recipientId, NotificationDto notification) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("type", FRAME_TYPE, "notification", notification));
        } catch (JsonProcessingException e) {
            log.warn("알림 프레임 직렬화 실패 — 저장은 됐으므로 실시간 전달만 건너뛴다 (notificationId={})",
                    notification.getId(), e);
            return;
        }
        groupSocketRegistry.sendToMember(recipientId, json);
    }
}
