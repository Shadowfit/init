package com.shadowfit.integration;

import com.shadowfit.controller.PublicHealthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 외부 가동 감시용 GET /health — 인증 없이 200, DB 가 안 붙으면 503, 상태 한 단어 외엔 내보내지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("공개 /health")
class PublicHealthIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("토큰 없이 200 {status:UP} — 실제 시큐리티 체인과 whitelist 를 지난다")
    void up_withoutAuth() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // 상태 외에는 아무것도 밖에 내지 않는다(DB 종류·버전·예외 메시지 등)
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("DB 연결을 못 얻으면 503 {status:DOWN} — 예외 메시지는 응답에 실리지 않는다")
    void down_whenConnectionFails() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("jdbc:mysql://secret-host:3306 refused"));

        ResponseEntity<Map<String, String>> res = new PublicHealthController(broken).health();

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).containsExactly(Map.entry("status", "DOWN"));
    }

    @Test
    @DisplayName("연결은 얻었지만 isValid 가 false 여도 503")
    void down_whenConnectionInvalid() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.isValid(2)).thenReturn(false);

        assertThat(new PublicHealthController(ds).health().getStatusCode().value()).isEqualTo(503);
    }
}
