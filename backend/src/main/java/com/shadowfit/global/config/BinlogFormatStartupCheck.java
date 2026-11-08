package com.shadowfit.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 기동 시 binlog 형식을 확인한다 — {@code STATEMENT} 면 기동을 거부한다 (#276).
 *
 * <p><b>왜 필요한가.</b> pose 저장({@code PoseDataService.savePoseDataBatch})은 #276 처방으로
 * READ COMMITTED 로 돈다. InnoDB 는 RC 트랜잭션의 쓰기를 STATEMENT 형식으로 binlog 에 적을 수 없어
 * 그 쓰기를 오류로 거부한다(MySQL 오류 1665). 데이터가 조용히 깨지는 것이 아니라 <b>pose 저장이 전부
 * 실패하는 장애</b>다. 그런데 지금 이 서비스는 binlog 형식을 어디에도 적지 않고 MySQL 8.0 기본값
 * {@code ROW} 에 기대고 있다(compose·my.cnf 모두 지정 없음). 누가 서버 설정을 바꾸거나 DB 를
 * 관리형으로 옮기며 파라미터가 달라지면, 그 장애는 운동 중인 사용자에게서 처음 드러난다.
 * 이 검사는 그 시점을 <b>배포 순간</b>으로 당긴다.
 *
 * <p><b>판정</b> — {@code ROW}·{@code MIXED} 는 통과(MIXED 는 RC 에서 행 형식으로 바꿔 적는다),
 * binlog 가 꺼져 있으면 형식과 무관하게 통과, {@code STATEMENT} 만 거부. 보는 값은 <b>세션</b> 값이다 —
 * 실제 쓰기는 이 풀의 연결에서 일어나고, 세션 값은 연결 시점의 전역 값을 물려받는다.
 *
 * <p><b>MySQL 이 아니면 건너뛴다</b> — 기본 테스트는 H2 라 {@code @@binlog_format} 이 없다.
 *
 * <p>{@link InitializingBean} 인 이유: {@code ApplicationRunner} 는 gRPC 서버가 이미 요청을 받기
 * 시작한 뒤에 돈다. 여기서 던지면 컨텍스트가 뜨기 전에 멈춘다.
 *
 * <p>분기와 근거: {@code docs/decisions/r276-lock-root-cause-fix.md} §2 ㄴ
 */
@Slf4j
@Component
public class BinlogFormatStartupCheck implements InitializingBean {

    private final DataSource dataSource;

    public BinlogFormatStartupCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            if (!"MySQL".equalsIgnoreCase(product)) {
                log.debug("binlog 형식 검사 건너뜀 — DB 가 MySQL 이 아니다: {}", product);
                return;
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@GLOBAL.log_bin, @@SESSION.binlog_format")) {
                rs.next();
                boolean logBin = rs.getInt(1) == 1;
                String format = rs.getString(2);
                check(logBin, format);
                log.info("binlog 형식 확인 — log_bin={} binlog_format={} (RC 저장과 함께 쓸 수 있다)", logBin, format);
            }
        }
    }

    /** 판정만 따로 — 서버 설정을 바꾸지 않고 거부 경로를 시험할 수 있게. */
    static void check(boolean logBin, String format) {
        if (logBin && "STATEMENT".equalsIgnoreCase(format)) {
            throw new IllegalStateException(
                    "binlog_format=STATEMENT 에서는 READ COMMITTED 로 도는 pose 저장이 전부 오류(1665)로 실패한다 (#276). "
                            + "MySQL 을 binlog_format=ROW(또는 MIXED)로 띄울 것 — docs/decisions/r276-lock-root-cause-fix.md §2 ㄴ");
        }
    }
}
