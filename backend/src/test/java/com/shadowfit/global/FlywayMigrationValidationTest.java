package com.shadowfit.global;

import com.shadowfit.support.MySqlContainerSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «마이그레이션 전부를 빈 MySQL 에 적용하면 엔티티가 그 스키마를 읽을 수 있는가» — 이 한 문장이
 * 기본 테스트 스위트에서 한 번도 검증된 적이 없었다.
 *
 * <p>기본 프로파일은 H2 + {@code ddl-auto: create-drop} 이라 Hibernate 가 <b>엔티티에서</b> 스키마를
 * 만든다. 그래서 마이그레이션이 틀려도(번호 중복 #653, 컬럼 누락, 타입 불일치) 초록불이 뜬다 —
 * {@code src/test/resources/application.yml} 주석이 스스로 적어둔 한계다. {@link SchemaEnumConsistencyTest}
 * 가 ENUM 한정으로 그 틈을 좁혔지만 나머지는 열려 있었다.
 *
 * <p>이 클래스는 {@code race} 프로파일(실 MySQL, Flyway 가 스키마 생성, {@code ddl-auto: validate})로
 * 뜨므로 <b>컨텍스트가 뜨는 것 자체</b>가 검증이다: Flyway 가 V1 부터 끝까지 성공하고, Hibernate 가
 * 모든 {@code @Entity} 의 테이블·컬럼을 그 스키마에서 찾았다는 뜻이다. 아래 테스트 메서드는 그
 * 사실을 «묵시적으로 통과» 가 아니라 «명시적으로 확인» 으로 바꾸는 역할이다.
 */
@SpringBootTest
@ActiveProfiles("race")
@DisplayName("Flyway 마이그레이션 ↔ 엔티티 정합 (실 MySQL)")
class FlywayMigrationValidationTest extends MySqlContainerSupport {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("빈 MySQL 에 마이그레이션이 전부 적용되고 미적용분이 없다")
    void allMigrationsApplied() {
        MigrationInfo[] all = flyway.info().all();
        assertThat(all).as("마이그레이션 파일이 하나도 안 잡혔다 — locations 설정을 의심할 것").isNotEmpty();

        List<String> notSucceeded = Arrays.stream(all)
                .filter(m -> m.getState() != MigrationState.SUCCESS)
                .map(m -> m.getVersion() + " " + m.getDescription() + " → " + m.getState())
                .toList();
        assertThat(notSucceeded).as("SUCCESS 가 아닌 마이그레이션").isEmpty();

        assertThat(flyway.info().pending()).as("미적용 마이그레이션").isEmpty();
        assertThat(flyway.info().current().getVersion())
                .as("현재 버전 = 가장 높은 파일 버전")
                .isEqualTo(all[all.length - 1].getVersion());
    }

    /**
     * {@code race} 프로파일이 존재하는 이유를 스키마 수준에서 못 박는다 — {@code pose_data} 는
     * 파티셔닝 때문에 FK 가 없다({@code docs/decisions/pose-data-partition-fk-tradeoff.md}). H2 는
     * 엔티티의 {@code @ManyToOne} 에서 FK 를 만들어버려 이 전제가 뒤집히고, 그 위에 선
     * {@code PoseDataOrphanRaceTest} 는 결과가 반대로 나온다. 누가 나중에 마이그레이션으로 FK 를
     * 붙이면 그 테스트가 «통과» 로 바뀌어 결함이 사라진 것처럼 보이므로, 전제 변화를 여기서 잡는다.
     */
    @Test
    @DisplayName("pose_data 에는 FK 가 없다 (파티션 표 — 고아 행 테스트의 전제)")
    void poseDataHasNoForeignKey() {
        Integer fkCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'pose_data'
                   AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """, Integer.class);
        assertThat(fkCount).isZero();

        Integer partitions = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.PARTITIONS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'pose_data'
                   AND PARTITION_NAME IS NOT NULL
                """, Integer.class);
        assertThat(partitions).as("pose_data 가 파티션 표다").isGreaterThan(1);
    }
}
