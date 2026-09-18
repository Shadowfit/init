package com.shadowfit.integration;

import com.shadowfit.dto.attendance.MyAttendanceResponseDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.exercise.MyAttendanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스트릭 카드 — 서비스는 «오늘»을 고정해 주 경계(월을 걸치는 주)·미래 세션·동률을 실제 쿼리로 고정하고,
 * HTTP 는 인증과 응답 형태(필드 이름·날짜 포맷·7칸)만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("내 출석·스트릭 카드 통합테스트")
class MyAttendanceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MyAttendanceService myAttendanceService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member me;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        me = memberRepository.saveAndFlush(Member.builder().email("me@test.com").username("me")
                .password(passwordEncoder.encode("password123")).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true).build());
    }

    @Test
    @DisplayName("주가 월을 걸쳐도 월~일 7칸, 오늘 이후는 false, 미래 start_time 은 오늘 기준 streak 에 안 섞인다")
    void weekAcrossMonthBoundary_andFutureIgnored() {
        LocalDate today = LocalDate.of(2026, 10, 1); // 목요일 — 이번 주는 9/28(월)~10/4(일)
        session(LocalDate.of(2026, 9, 28), Status.COMPLETED);
        session(LocalDate.of(2026, 9, 30), Status.COMPLETED);
        session(today, Status.COMPLETED);
        session(LocalDate.of(2026, 10, 3), Status.COMPLETED); // 미래(시계 어긋남 등) — 카드에 안 보인다

        MyAttendanceResponseDto card = myAttendanceService.myAttendance(me.getId(), today);

        assertThat(card.getToday()).isEqualTo(today);
        assertThat(card.isAttendedToday()).isTrue();
        assertThat(card.getCurrentStreak()).isEqualTo(2); // 9/30·10/1 — 9/29 가 비어 9/28 은 안 이어진다
        assertThat(card.getCurrentStreakStart()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(card.getThisWeek()).hasSize(7);
        assertThat(card.getThisWeek().get(0).getDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(card.getThisWeek().get(6).getDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(card.getThisWeek().stream().map(MyAttendanceResponseDto.Day::isAttended).toList())
                .containsExactly(true, false, true, true, false, false, false); // 10/3 은 오늘 이후라 false
    }

    @Test
    @DisplayName("최장 기록 — 과거 3일 구간과 현재 3일 구간이 동률이면 현재(최근) 구간, 어제까지면 «갱신 중» 판정이 성립")
    void longestTie_mostRecentSoRenewalIsDetectable() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        for (int i = 20; i <= 22; i++) {
            session(today.minusDays(i), Status.COMPLETED);
        }
        session(today.minusDays(3), Status.COMPLETED);
        session(today.minusDays(2), Status.COMPLETED);
        session(today.minusDays(1), Status.COMPLETED);

        MyAttendanceResponseDto card = myAttendanceService.myAttendance(me.getId(), today);

        assertThat(card.isAttendedToday()).isFalse();
        assertThat(card.getCurrentStreak()).isEqualTo(3);
        assertThat(card.getLongestStreak()).isEqualTo(3);
        assertThat(card.getLongestStreakStart()).isEqualTo(today.minusDays(3));
        assertThat(card.getLongestStreakEnd()).isEqualTo(today.minusDays(1));
    }

    @Test
    @DisplayName("기록이 없으면 0·null·7칸 전부 false")
    void empty() {
        MyAttendanceResponseDto card = myAttendanceService.myAttendance(me.getId(), LocalDate.of(2026, 10, 1));

        assertThat(card.getCurrentStreak()).isZero();
        assertThat(card.getCurrentStreakStart()).isNull();
        assertThat(card.getLongestStreak()).isZero();
        assertThat(card.getLongestStreakStart()).isNull();
        assertThat(card.getLongestStreakEnd()).isNull();
        assertThat(card.getThisWeek()).hasSize(7).noneMatch(MyAttendanceResponseDto.Day::isAttended);
    }

    @Test
    @DisplayName("GET /attendance/mine — JWT 로 내 것, 날짜는 yyyy-MM-dd 문자열, 7칸")
    void http_shape() throws Exception {
        LocalDate today = LocalDate.now();
        session(today, Status.COMPLETED);
        session(today.minusDays(1), Status.COMPLETED);

        mockMvc.perform(get("/attendance/mine").header("Authorization", "Bearer " + tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.attendedToday").value(true))
                .andExpect(jsonPath("$.currentStreak").value(2))
                .andExpect(jsonPath("$.currentStreakStart").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.longestStreak").value(2))
                .andExpect(jsonPath("$.longestStreakStart").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.longestStreakEnd").value(today.toString()))
                .andExpect(jsonPath("$.thisWeek", hasSize(7)))
                .andExpect(jsonPath("$.thisWeek[0].date").exists())
                .andExpect(jsonPath("$.thisWeek[0].attended").isBoolean());
    }

    @Test
    @DisplayName("GET /attendance/mine — 토큰 없으면 거부")
    void http_unauthenticated() throws Exception {
        mockMvc.perform(get("/attendance/mine")).andExpect(status().is4xxClientError());
    }

    private void session(LocalDate date, Status s) {
        sessionRepository.saveAndFlush(Session.builder().member(me).exercise(exercise)
                .startTime(date.atTime(10, 0)).endTime(date.atTime(10, 20)).status(s).totalReps(20).build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }
}
