package com.shadowfit.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * 외부 가동 감시(uptime monitor)용 «살아 있나» 엔드포인트 — 인증 없음(security.whitelist), 포트 8080.
 *
 * <p><b>왜 따로 두나.</b> {@code /actuator/health} 는 관리 포트 9090 에서만 서비스되고 prod compose 는 그 포트를 호스트에 열지 않는다
 * (지표가 공개되지 않는 근거가 «포트 경계» 이므로 열면 안 된다). 그래서 밖에서 찌르는 감시 서비스는 서비스가 죽었는지 알 길이 없었다.
 *
 * <p><b>무엇을 보나.</b> DB 연결 하나가 살아 있는지({@link Connection#isValid}, 2초)뿐이다. 서버 프로세스가 응답하고 DB 가 붙어 있다 = 앱이
 * 쓸 수 있다. AI 서버·외부 API 는 보지 않는다 — AI 가 느려졌다고 «서비스 다운» 으로 울리면 오탐이고, 그 구분은 지표 쪽 일이다.
 *
 * <p><b>무엇을 내보내지 않나.</b> 상태 한 단어뿐이다. DB 종류·버전·예외 메시지는 밖에 보일 이유가 없다(실패 사유는 서버 로그에만).
 * 200 {@code {"status":"UP"}} / 503 {@code {"status":"DOWN"}} — 감시 서비스는 HTTP 상태로 판정한다.
 */
@Hidden
@Slf4j
@RestController
@RequiredArgsConstructor
public class PublicHealthController {

    private static final int DB_VALID_TIMEOUT_SEC = 2;

    private final DataSource dataSource;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(DB_VALID_TIMEOUT_SEC)) {
                return ResponseEntity.ok(Map.of("status", "UP"));
            }
            log.warn("[health] DB 연결이 isValid 를 통과하지 못했다");
        } catch (Exception e) {
            log.warn("[health] DB 확인 실패: {}", e.toString());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }
}
