package com.shadowfit.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 판정 규칙만 — 실 MySQL 에서의 조회와 전제(오류 1665)는 {@link BinlogFormatStartupCheckRaceTest}. */
@DisplayName("binlog 형식 기동 검사 — 판정 (#276)")
class BinlogFormatStartupCheckTest {

    @Test
    @DisplayName("binlog 가 켜져 있고 STATEMENT 면 기동을 거부한다")
    void statementIsRejected() {
        assertThatThrownBy(() -> BinlogFormatStartupCheck.check(true, "STATEMENT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1665");
        assertThatThrownBy(() -> BinlogFormatStartupCheck.check(true, "statement"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ROW·MIXED 는 통과한다")
    void rowAndMixedPass() {
        assertThatCode(() -> BinlogFormatStartupCheck.check(true, "ROW")).doesNotThrowAnyException();
        assertThatCode(() -> BinlogFormatStartupCheck.check(true, "MIXED")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("binlog 가 꺼져 있으면 형식과 무관하게 통과한다")
    void binlogOffPasses() {
        assertThatCode(() -> BinlogFormatStartupCheck.check(false, "STATEMENT")).doesNotThrowAnyException();
    }
}
