package com.shadowfit.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기동 시 JVM 시간대와 JDBC 연결 시간대가 같은지 확인한다 — 다르면 기동을 거부한다 (#846).
 *
 * <p><b>왜 필요한가.</b> 같은 «세션 시작 시각» 을 두 경로가 쓴다. {@code exercise_sessions.start_time} 은
 * Hibernate 가 쓰면서 JVM 시간대 → URL 의 {@code serverTimezone} 으로 <b>변환</b>하고,
 * {@code pose_data.created_at} 은 {@code PoseDataService} 가 JdbcTemplate {@code setObject(LocalDateTime)} 로
 * <b>변환 없이</b> 쓴다. 두 시간대가 같으면 변환이 없어 값이 같고, 다르면 시차만큼 어긋난다. 완료 집계
 * ({@code PoseDataRepository.findRepSummaries})는 {@code created_at = 세션 시작 시각} 으로 rep 을 찾으므로,
 * 어긋나면 <b>오류 없이</b> 싱크 통계·세트·리포트가 빈다. CI(JVM UTC)에서 이 일이 실제로 났다.
 *
 * <p>운영·dev 는 컨테이너 {@code TZ=Asia/Seoul} 로 맞아 있지만, 그 전제가 코드 어디에도 강제돼 있지
 * 않았다. 이 검사가 그 전제를 기동 시점에 강제한다.
 *
 * <p><b>판정</b> — URL 에 {@code connectionTimeZone}(8.0.23+) 또는 {@code serverTimezone} 이 있으면 그 값과
 * JVM 기본 시간대의 규칙이 같아야 한다. 둘 다 없으면 드라이버가 JVM 시간대를 그대로 써 변환이 없으므로 통과.
 * MySQL 이 아니면 건너뛴다.
 */
@Slf4j
@Component
public class JvmTimezoneStartupCheck implements InitializingBean {

    private static final Pattern ZONE_PARAM =
            Pattern.compile("[?&](connectionTimeZone|serverTimezone)=([^&]+)", Pattern.CASE_INSENSITIVE);

    private final DataSource dataSource;

    public JvmTimezoneStartupCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        String url;
        try (Connection c = dataSource.getConnection()) {
            if (!"MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName())) {
                return;
            }
            url = c.getMetaData().getURL();
        }
        ZoneId jvm = ZoneId.systemDefault();
        check(jvm, url);
        log.info("JVM 시간대 확인 — {} (JDBC 연결 시간대와 같다)", jvm);
    }

    /** 판정만 따로 — JVM 시간대를 바꾸지 않고 거부 경로를 시험할 수 있게. */
    static void check(ZoneId jvm, String jdbcUrl) {
        Matcher m = ZONE_PARAM.matcher(jdbcUrl == null ? "" : jdbcUrl);
        String connection = null;
        while (m.find()) {
            // connectionTimeZone 이 serverTimezone 보다 우선한다(Connector/J 8.0.23+).
            if (connection == null || m.group(1).equalsIgnoreCase("connectionTimeZone")) {
                connection = m.group(2);
            }
        }
        if (connection == null || connection.equalsIgnoreCase("LOCAL")) {
            return;
        }
        ZoneId db;
        try {
            db = ZoneId.of(connection);
        } catch (DateTimeException e) {
            throw new IllegalStateException("JDBC URL 의 시간대를 해석하지 못했다: " + connection, e);
        }
        if (!db.getRules().equals(jvm.getRules())) {
            throw new IllegalStateException(
                    "JVM 시간대(" + jvm + ")가 JDBC 연결 시간대(" + db + ")와 다르다 — 세션 시작 시각과 pose_data.created_at 이 "
                            + "어긋나 완료 집계가 rep 을 못 찾는다 (#846). 컨테이너에 TZ=" + db + " 를 주거나 -Duser.timezone=" + db + " 로 띄울 것");
        }
    }
}
