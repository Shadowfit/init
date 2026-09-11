package com.shadowfit.support;

import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

/**
 * 실 MySQL 이 필요한 테스트의 공통 베이스 — {@code race} 프로파일과 짝이다.
 *
 * <p>H2(MySQL 모드)로는 원리상 검증이 안 되는 것들이 여기로 온다: {@code JSON_TABLE},
 * 파티션 표에 FK 가 없어서 생기는 고아 행, 벤더 고유 제약명 형식, 그리고 «Flyway 마이그레이션이
 * 엔티티와 맞는가» 자체. 예전에는 3307 에 컨테이너를 손으로 띄우고 {@code V*.sql} 을 순서대로
 * 부어 넣은 뒤 {@code -Drace.mysql=true} 로 열어야 했고, 그래서 CI 는 이 테스트들을 한 번도
 * 돌린 적이 없었다. 이제는 Testcontainers 가 {@code mysql:8.0} 을 띄우고 Flyway 가 스키마를
 * 만든다 — 마이그레이션이 곧 테스트 픽스처다.
 *
 * <p><b>컨테이너는 JVM 당 하나다.</b> {@code @Container} 로 두면 JUnit 이 테스트 클래스마다
 * 새로 띄우므로(부모 클래스의 static 필드도 클래스 단위로 관리된다) 일부러 안 붙였다. 정적
 * 초기화에서 한 번 시작하고 종료는 Testcontainers 의 Ryuk 이 JVM 이 내려갈 때 회수한다.
 * Spring 컨텍스트도 같은 설정이면 캐시되므로, 이 베이스를 상속한 클래스들은 컨테이너 1개 +
 * 컨텍스트 1개를 나눠 쓴다. 그래서 각 테스트는 자기가 남긴 행을 {@code @AfterEach} 에서
 * 직접 지워야 한다 — 상속한 테스트들이 원래 그렇게 돼 있다.
 *
 * <p><b>Docker 가 없으면 건너뛴다</b>({@code disabledWithoutDocker}). 실패가 아니라 «건너뜀» 으로
 * 보고되므로 로컬에 Docker 가 없어도 기본 스위트는 그대로 초록불이고, CI(ubuntu 러너)에서는
 * 항상 돈다. 특정 상황에서 강제로 끄려면 {@code -Dmysql.container=false}.
 *
 * <p>서버 옵션은 {@code docker-compose.yml} 의 mysql 서비스와 맞춘다 — 문자셋·콜레이션이 다르면
 * 문자열 비교(대소문자 무시)가 운영과 달라져 테스트가 다른 것을 검증하게 된다.
 * {@code serverTimezone}·{@code rewriteBatchedStatements} 도 운영 URL 과 같게 둔다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisabledIfSystemProperty(named = "mysql.container", matches = "false",
        disabledReason = "-Dmysql.container=false 로 실 MySQL 테스트를 명시적으로 껐다")
public abstract class MySqlContainerSupport {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--skip-character-set-client-handshake")
            .withUrlParam("serverTimezone", "Asia/Seoul")
            .withUrlParam("characterEncoding", "UTF-8")
            .withUrlParam("rewriteBatchedStatements", "true")
            // 기본 대기(120초)는 이 프로젝트의 로컬 박스(i3-6100, 다른 컨테이너와 동거)에서 모자랐다 —
            // 2026-09-11 실측: 빈 mysql:8.0 이 ready 까지 148초. CI 러너는 그보다 빠르므로 상한만 넉넉히.
            .withStartupTimeout(Duration.ofSeconds(300))
            .withConnectTimeoutSeconds(300);

    static {
        MYSQL.start();
    }
}
