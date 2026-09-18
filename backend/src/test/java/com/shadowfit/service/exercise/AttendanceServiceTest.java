package com.shadowfit.service.exercise;

import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 커서 알고리즘만 본다(페이지 경계·중복·끊김·앵커). 실제 쿼리·인덱스는 {@link AttendanceServiceIntegrationTest}.
 */
@DisplayName("AttendanceService 단위 테스트 — 커서 streak")
class AttendanceServiceTest {

    private static final Long MEMBER = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    private final SessionRepository repo = mock(SessionRepository.class);
    private final AttendanceService service = new AttendanceService(repo);

    @Test
    @DisplayName("오늘·어제·그제 완료, 4일 전 비면 3 — 첫 페이지에서 끝나고 두 번째 페이지는 안 읽는다")
    void streak_stopsAtFirstGapWithinFirstPage() {
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(daysAgo(TODAY, 0, 1, 2, 5, 6));

        assertThat(service.currentStreak(MEMBER, TODAY)).isEqualTo(3);
        verify(repo, times(1)).findCompletedStartTimesBefore(any(), any(), any(), any());
    }

    @Test
    @DisplayName("오늘은 아직 안 했고 어제부터 이어지면 어제를 앵커로 센다 (관대한 규칙)")
    void streak_anchorsOnYesterdayWhenTodayMissing() {
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(daysAgo(TODAY, 1, 2));

        assertThat(service.currentStreak(MEMBER, TODAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("최신 출석이 그제면 이어지는 streak 이 없다 → 0")
    void streak_zeroWhenLatestIsTwoDaysAgo() {
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(daysAgo(TODAY, 2, 3, 4));

        assertThat(service.currentStreak(MEMBER, TODAY)).isZero();
    }

    @Test
    @DisplayName("기록이 없으면 0")
    void streak_zeroWhenNoSessions() {
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(List.of());

        assertThat(service.currentStreak(MEMBER, TODAY)).isZero();
    }

    @Test
    @DisplayName("같은 날 세션이 여러 개여도 하루로 센다")
    void streak_countsOneDayOnceDespiteMultipleSessions() {
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(List.of(
                        TODAY.atTime(20, 0), TODAY.atTime(9, 0),
                        TODAY.minusDays(1).atTime(18, 0), TODAY.minusDays(1).atTime(7, 0)));

        assertThat(service.currentStreak(MEMBER, TODAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("한 페이지(FETCH_BATCH)를 꽉 채워도 안 끊기면 마지막 시각을 커서로 다음 페이지를 읽는다")
    void streak_pagesPastFetchBatchUntilGap() {
        int batch = AttendanceService.FETCH_BATCH;
        List<LocalDateTime> first = daysAgo(TODAY, IntStream.range(0, batch).toArray());
        List<LocalDateTime> second = daysAgo(TODAY, batch, batch + 1, batch + 3); // batch+2 가 빈다
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(first, second);

        assertThat(service.currentStreak(MEMBER, TODAY)).isEqualTo(batch + 2);

        ArgumentCaptor<LocalDateTime> cursor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo, times(2)).findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), cursor.capture(), any());
        // 첫 호출은 «내일 0시 이전», 두 번째는 첫 페이지의 마지막 시각 이전.
        assertThat(cursor.getAllValues().get(0)).isEqualTo(TODAY.plusDays(1).atStartOfDay());
        assertThat(cursor.getAllValues().get(1)).isEqualTo(first.get(first.size() - 1));
    }

    @Test
    @DisplayName("페이지가 꽉 찼는데 다음 페이지가 비면 거기까지가 답이다")
    void streak_fullPageThenEmptyPage() {
        int batch = AttendanceService.FETCH_BATCH;
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(daysAgo(TODAY, IntStream.range(0, batch).toArray()), List.of());

        assertThat(service.currentStreak(MEMBER, TODAY)).isEqualTo(batch);
    }

    @Test
    @DisplayName("currentStreakRun — 길이와 함께 시작일·끝(앵커)을 준다, 시작일은 걷기가 멈춘 자리")
    void streakRun_carriesStartAndAnchor() {
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(daysAgo(TODAY, 1, 2, 3, 6));

        AttendanceService.StreakRun run = service.currentStreakRun(MEMBER, TODAY);
        assertThat(run.length()).isEqualTo(3);
        assertThat(run.start()).isEqualTo(TODAY.minusDays(3));
        assertThat(run.end()).isEqualTo(TODAY.minusDays(1)); // 오늘 안 했으니 앵커는 어제
    }

    @Test
    @DisplayName("currentStreakRun — 기록이 없으면 NONE(0, null, null)")
    void streakRun_noneWhenEmpty() {
        when(repo.findCompletedStartTimesBefore(eq(MEMBER), eq(Status.COMPLETED), any(), any()))
                .thenReturn(List.of());

        assertThat(service.currentStreakRun(MEMBER, TODAY)).isEqualTo(AttendanceService.StreakRun.NONE);
    }

    @Test
    @DisplayName("longestStreakRun — 구간이 여럿이면 가장 긴 것, 현재 streak 과 무관하게 과거 것도 잡는다")
    void longest_picksLongestRunAnywhereInHistory() {
        when(repo.findDistinctDatesBefore(eq(MEMBER), eq(Status.COMPLETED), any()))
                .thenReturn(dates(TODAY, 40, 39, 38, 37, 36, 20, 19, 1, 0)); // 5 / 2 / 2

        AttendanceService.StreakRun run = service.longestStreakRun(MEMBER, TODAY);
        assertThat(run.length()).isEqualTo(5);
        assertThat(run.start()).isEqualTo(TODAY.minusDays(40));
        assertThat(run.end()).isEqualTo(TODAY.minusDays(36));
    }

    @Test
    @DisplayName("longestStreakRun — 동률이면 가장 최근 구간 («갱신 중» 판정이 되게)")
    void longest_tieGoesToMostRecent() {
        when(repo.findDistinctDatesBefore(eq(MEMBER), eq(Status.COMPLETED), any()))
                .thenReturn(dates(TODAY, 30, 29, 28, 2, 1, 0)); // 3 / 3

        AttendanceService.StreakRun run = service.longestStreakRun(MEMBER, TODAY);
        assertThat(run.length()).isEqualTo(3);
        assertThat(run.start()).isEqualTo(TODAY.minusDays(2));
        assertThat(run.end()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("longestStreakRun — 기록이 없으면 NONE, 하루뿐이면 1")
    void longest_noneAndSingleDay() {
        when(repo.findDistinctDatesBefore(eq(MEMBER), eq(Status.COMPLETED), any())).thenReturn(List.of());
        assertThat(service.longestStreakRun(MEMBER, TODAY)).isEqualTo(AttendanceService.StreakRun.NONE);

        when(repo.findDistinctDatesBefore(eq(MEMBER), eq(Status.COMPLETED), any())).thenReturn(dates(TODAY, 7));
        assertThat(service.longestStreakRun(MEMBER, TODAY))
                .isEqualTo(new AttendanceService.StreakRun(1, TODAY.minusDays(7), TODAY.minusDays(7)));
    }

    /** 오늘 기준 «n일 전» 들의 날짜(오름차순으로 넘길 것 — 쿼리가 ORDER BY 로 보장하는 순서). */
    private static List<java.sql.Date> dates(LocalDate today, int... offsetsDescending) {
        return IntStream.of(offsetsDescending).mapToObj(d -> java.sql.Date.valueOf(today.minusDays(d))).toList();
    }

    /** 오늘 기준 «n일 전» 들의 시작 시각(최신순). */
    private static List<LocalDateTime> daysAgo(LocalDate today, int... offsets) {
        return IntStream.of(offsets).mapToObj(d -> today.minusDays(d).atTime(12, 0)).toList();
    }
}
