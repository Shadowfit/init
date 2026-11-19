package com.shadowfit.service.exercise;

import com.shadowfit.support.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PoseDataPartitionScheduler} 가 실제 MySQL 에서 DDL 을 <b>실행</b>해 보는 테스트.
 *
 * <p>{@code PoseDataPartitionSchedulerTest} 는 «무엇을 드롭/생성할지» 결정 로직만 본다(목 JdbcTemplate).
 * 그 SQL 이 MySQL 에서 실제로 통과하는지 — {@code REORGANIZE PARTITION pfuture INTO (...)} 문법,
 * {@code UNIX_TIMESTAMP('yyyy-MM-dd 00:00:00')} 경계식, {@code information_schema.partitions} 조회 —
 * 는 한 번도 실행된 적이 없었다. 되돌릴 수 없는 작업(DROP PARTITION)이라 «돌려 보니 문법 오류» 를
 * 운영 새벽 4시에 처음 알게 되는 구조였다.
 *
 * <p><b>격리.</b> race 테스트들은 컨테이너 하나를 같이 쓴다. 실제 {@code pose_data} 의 파티션을
 * 지우면 다른 테스트가 영향을 받으므로, 같은 컨테이너에 <b>스크래치 스키마</b>를 만들고
 * {@code CREATE TABLE ... LIKE} 로 Flyway 가 만든 진짜 정의(컬럼·PK·유니크 키)를 복사한 뒤
 * 파티션만 «오늘 기준 상대 월» 로 다시 짠다. 스케줄러는 {@code DATABASE()} 와 무한정 테이블명
 * {@code pose_data} 를 쓰므로 스크래치 스키마를 기본 DB 로 하는 커넥션을 주면 그대로 그쪽을 본다 —
 * main 코드는 바꾸지 않는다.
 *
 * <p>⚠️ 월 경계: 스케줄러는 실행 시점의 {@code YearMonth.now(Asia/Seoul)} 를 쓴다. 테스트가 정확히
 * 자정 월 넘김에 걸리면 기대값과 한 달 어긋날 수 있다(드물고, 재실행으로 사라진다).
 */
@SpringBootTest(properties = {"scheduling.enabled=false", "grpc.server.port=-1"})
@ActiveProfiles("race")
@DisplayName("pose_data 파티션 유지보수 — DROP/REORGANIZE 실제 실행 (실 MySQL)")
class PoseDataPartitionMaintenanceRaceTest extends MySqlContainerSupport {

    private static final String SCRATCH = "pose_partition_it";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BOUND = DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00");
    // 스케줄러 기본값과 같게 둔다(@Value 기본값: retention-buffer-months=1, lookahead-months=2).
    private static final int RETENTION_BUFFER_MONTHS = 1;
    private static final int LOOKAHEAD_MONTHS = 2;

    /** Flyway 를 돌리기 위해 컨텍스트를 띄운다 — 복사 원본 {@code pose_data} 가 거기서 만들어진다. */
    @Autowired private JdbcTemplate appJdbc;

    private JdbcTemplate root;
    private JdbcTemplate scratch;
    private YearMonth now;

    @BeforeEach
    void setUp() {
        now = YearMonth.now(SEOUL);
        root = new JdbcTemplate(dataSource(MYSQL.getDatabaseName()));
        root.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        root.execute("CREATE DATABASE " + SCRATCH);
        String source = MYSQL.getDatabaseName() + ".pose_data";
        assertThat(appJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'pose_data'", Integer.class))
                .as("Flyway 가 만든 복사 원본이 있어야 한다").isEqualTo(1);
        root.execute("CREATE TABLE " + SCRATCH + ".pose_data LIKE " + source);

        // 파티션 배치: 이름 패턴 밖(p_legacy) · now-3 · now-2 · now-1 · now · pfuture
        // → 기대: now-3·now-2 드롭(보존은 now-1 부터), p_legacy 는 파싱 실패라 유지,
        //   now+1·now+2 생성(lookahead 2), pfuture 는 끝에 그대로.
        root.execute("ALTER TABLE " + SCRATCH + ".pose_data PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) ("
                + "PARTITION p_legacy VALUES LESS THAN (" + upper(now.minusMonths(4)) + "), "
                + partition(now.minusMonths(3)) + ", "
                + partition(now.minusMonths(2)) + ", "
                + partition(now.minusMonths(1)) + ", "
                + partition(now) + ", "
                + "PARTITION pfuture VALUES LESS THAN MAXVALUE)");

        scratch = new JdbcTemplate(dataSource(SCRATCH));
        insertRowIn(now.minusMonths(5), 1);   // p_legacy
        insertRowIn(now.minusMonths(3), 2);   // 드롭될 달
        insertRowIn(now.minusMonths(1), 3);   // 버퍼 — 남아야 한다
        insertRowIn(now, 4);                  // 이번 달 — 남아야 한다
    }

    @AfterEach
    void tearDown() {
        root.execute("DROP DATABASE IF EXISTS " + SCRATCH);
    }

    @Test
    @DisplayName("만료 달만 DROP, 미래 달은 REORGANIZE 로 생성 — 행은 만료 달 것만 사라진다")
    void maintain_dropsExpiredAndCreatesFuture() {
        scheduler().checkAndMaintainPartitions();

        assertThat(partitionNames()).containsExactly(
                "p_legacy",
                name(now.minusMonths(1)),
                name(now),
                name(now.plusMonths(1)),
                name(now.plusMonths(2)),
                "pfuture");
        assertThat(sessionIds())
                .as("드롭된 달(now-3)의 행만 사라지고, 이름 패턴 밖 파티션·버퍼·이번 달 행은 남는다")
                .containsExactlyInAnyOrder(1L, 3L, 4L);
    }

    @Test
    @DisplayName("두 번 돌려도 결과가 같다 — 매일 도는 잡이라 멱등이어야 한다")
    void maintain_isIdempotent() {
        PoseDataPartitionScheduler scheduler = scheduler();
        scheduler.checkAndMaintainPartitions();
        List<String> afterFirst = partitionNames();

        scheduler.checkAndMaintainPartitions();

        assertThat(partitionNames()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("새로 만든 미래 파티션 경계가 맞다 — 다음 달 1일 0시 행이 그 달 파티션에 들어간다")
    void createdPartition_boundaryIsMonthStart() {
        scheduler().checkAndMaintainPartitions();
        YearMonth next = now.plusMonths(1);
        insertRowIn(next, 5);

        // information_schema 의 table_rows 는 추정치라 PARTITION 절로 직접 센다.
        Integer rows = scratch.queryForObject(
                "SELECT COUNT(*) FROM pose_data PARTITION (" + name(next) + ") WHERE session_id = 5", Integer.class);
        assertThat(rows).as("다음 달 1일 0시 행은 %s 에 들어가야 한다", name(next)).isEqualTo(1);
    }

    private PoseDataPartitionScheduler scheduler() {
        return new PoseDataPartitionScheduler(scratch, RETENTION_BUFFER_MONTHS, LOOKAHEAD_MONTHS);
    }

    /** 그 달 1일 0시 행 — 파티션 경계 바로 위라 경계식이 틀리면 옆 파티션으로 간다. */
    private void insertRowIn(YearMonth month, long sessionId) {
        scratch.update("INSERT INTO pose_data (session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, created_at) "
                        + "VALUES (?, 1, 0.0, JSON_ARRAY(), 50.00, ?)",
                sessionId, month.atDay(1).atStartOfDay());
    }

    private List<String> partitionNames() {
        return scratch.queryForList("SELECT partition_name FROM information_schema.partitions "
                + "WHERE table_schema = DATABASE() AND table_name = 'pose_data' ORDER BY partition_ordinal_position", String.class);
    }

    private List<Long> sessionIds() {
        return scratch.queryForList("SELECT session_id FROM pose_data", Long.class);
    }

    private static String partition(YearMonth month) {
        return "PARTITION " + name(month) + " VALUES LESS THAN (" + upper(month) + ")";
    }

    private static String upper(YearMonth month) {
        LocalDateTime bound = month.plusMonths(1).atDay(1).atStartOfDay();
        return "UNIX_TIMESTAMP('" + bound.format(BOUND) + "')";
    }

    private static String name(YearMonth month) {
        return "p%04d_%02d".formatted(month.getYear(), month.getMonthValue());
    }

    /** root 로 붙는다 — 앱 계정은 자기 스키마 권한뿐이라 CREATE DATABASE 를 못 한다. */
    private static DriverManagerDataSource dataSource(String database) {
        String url = MYSQL.getJdbcUrl().replaceFirst("/" + MYSQL.getDatabaseName() + "(\\?|$)", "/" + database + "$1");
        return new DriverManagerDataSource(url, "root", MYSQL.getPassword());
    }
}
