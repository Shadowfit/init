package com.shadowfit.global.config;

import com.shadowfit.support.MySqlContainerSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 실 MySQL 에서 — ① 검사가 기본 설정(8.0 기본값 ROW)을 통과시키는가, ② 검사가 막으려는 장애가
 * 실제로 있는가(STATEMENT + RC 쓰기 = 오류 1665).
 *
 * <p>② 가 이 검사의 존재 이유다. 이 전제가 MySQL 버전에서 바뀌면(예: 거부 대신 조용히 다른 형식으로
 * 적기) 검사는 쓸모없는 기동 거부가 되므로, 그때 이 테스트가 먼저 깨지게 둔다.
 */
@SpringBootTest
@ActiveProfiles("race")
@DisplayName("binlog 형식 기동 검사 (실 MySQL, #276)")
class BinlogFormatStartupCheckRaceTest extends MySqlContainerSupport {

    @Autowired
    BinlogFormatStartupCheck check;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("기본 설정의 MySQL 8.0 은 binlog 가 켜져 있고 ROW 라 검사를 통과한다")
    void defaultServerPasses() {
        assertThat(jdbc.queryForObject("SELECT @@GLOBAL.log_bin", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT @@SESSION.binlog_format", String.class)).isEqualTo("ROW");
        assertThatCode(check::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("전제: binlog_format=STATEMENT 에서 READ COMMITTED 쓰기는 오류 1665 로 거부된다")
    void statementWithReadCommittedWriteFails() throws SQLException {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        // binlog_format 을 세션에서 바꾸려면 SYSTEM_VARIABLES_ADMIN 이 필요해 root 로 붙는다.
        // Testcontainers 의 MySQLContainer 는 root 암호를 앱 계정 암호와 같게 둔다.
        try (Connection root = DriverManager.getConnection(hikari.getJdbcUrl(), "root", hikari.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP TABLE IF EXISTS binlog_rc_probe");
            st.execute("CREATE TABLE binlog_rc_probe (id INT PRIMARY KEY) ENGINE=InnoDB");
            try {
                st.execute("SET SESSION binlog_format = 'STATEMENT'");
                st.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
                root.setAutoCommit(false);
                SQLException e = catchThrowableOfType(
                        () -> st.executeUpdate("INSERT INTO binlog_rc_probe VALUES (1)"), SQLException.class);
                assertThat((Throwable) e).as("STATEMENT + RC 쓰기가 거부되지 않았다 — 검사의 전제가 바뀌었다").isNotNull();
                assertThat(e.getErrorCode()).isEqualTo(1665);
                root.rollback();
            } finally {
                root.setAutoCommit(true);
                st.execute("SET SESSION binlog_format = 'ROW'");
                st.execute("DROP TABLE IF EXISTS binlog_rc_probe");
            }
        }
    }
}
