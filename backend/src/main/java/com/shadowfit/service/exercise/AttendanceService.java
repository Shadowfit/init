package com.shadowfit.service.exercise;

import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 출석(«그날 운동했다»)의 단일 정의와 그 파생값 — 오늘 했는지, 며칠째 연속인지.
 *
 * <p><b>정의</b> (docs/decisions/social-cheer-and-group-feed.md §3-B, 2026-09-11 confirm):
 * 출석 = 그날 {@code status = COMPLETED} 인 세션이 1건 이상. 날짜 귀속은 {@code start_time} 의
 * 서버 LocalDate. 원천은 {@code exercise_sessions} 그대로 — 파생 테이블을 두지 않는다
 * ({@code daily_logs} 는 완료 시 더하기만 하고 삭제를 안 따라가서 출석 원천으로 승격하지 않았다, #718).
 * 주간 요약·목표·패턴 분석이 이미 COMPLETED 만 세므로 이 정의는 새 것이 아니라 정렬이다.
 *
 * <p><b>연속일수는 창 없이 최신순 커서로 센다</b> (§3-B 하위 결정 C). 오늘부터 거꾸로 하루씩 짚어
 * 처음 비는 날에서 멈추므로, 읽는 행수가 회원의 기록 나이가 아니라 <b>답의 크기</b>(streak 길이)에
 * 비례한다 — 3년치 기록이 있어도 3일째면 3일치 근처만 읽는다. 조회는
 * {@code (member_id, status, start_time)} 인덱스를 뒤에서부터 걸으며 LIMIT 에서 멈추는 모양이고,
 * 그 비용이 계정 크기와 무관한 상수임은 {@code recommendation-algorithm.md} §10 이 같은 인덱스로
 * 실측했다(1,680세션 계정, 읽은 행 3, 0.4ms).
 *
 * <p>«오늘 안 했으면 어제부터 센다»는 {@code PatternAnalysisService.calculateStreak} 와 같은 관대한
 * 규칙(08-30 confirm) — 아침에 열었다고 streak 이 끊긴 것처럼 보이지 않게. 다만 그쪽은 «최근 4주
 * 안에서의 연속»이라는 창 있는 별개 정의라 그대로 두고, 여기는 캘린더·모임 친구 현황·출석이 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {

    /**
     * 한 번에 들고 오는 세션 시작 시각 수. 결과(streak)를 바꾸지 않고 왕복 횟수만 좌우하는 배치
     * 크기다 — 임계값이 아니다. 하루 1세션이면 한 달치가 한 페이지라, 한 달 넘게 매일 한 사람만
     * 두 번째 페이지를 읽는다.
     */
    static final int FETCH_BATCH = 31;

    private final SessionRepository sessionRepository;

    /** 그날 COMPLETED 세션이 1건 이상인가. */
    public boolean attendedOn(Long memberId, LocalDate date) {
        return sessionRepository.existsByMemberIdAndStatusAndStartTimeBetween(
                memberId, Status.COMPLETED, date.atStartOfDay(), date.atTime(23, 59, 59));
    }

    /** 여러 회원 중 그날 출석한 회원 id 집합 — 쿼리 한 번. 빈 입력이면 빈 집합. */
    public Set<Long> attendedOn(Collection<Long> memberIds, LocalDate date) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        return sessionRepository.findMemberIdsWithStatusBetween(
                memberIds, Status.COMPLETED, date.atStartOfDay(), date.atTime(23, 59, 59));
    }

    /**
     * 기간 안 날짜별 출석 인원 — 여러 회원 중 그날 COMPLETED 세션이 있는 사람 수. 출석 0 인 날은 키가
     * 없다(호출자가 채운다). 빈 입력이면 빈 맵.
     */
    public Map<LocalDate, Integer> attendeeCountsByDay(Collection<Long> memberIds, LocalDate from, LocalDate to) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        Map<LocalDate, Integer> counts = new HashMap<>();
        for (Object[] row : sessionRepository.countDistinctMembersByDay(
                memberIds, Status.COMPLETED, from.atStartOfDay(), to.atTime(23, 59, 59))) {
            counts.put(((java.sql.Date) row[0]).toLocalDate(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * 연속 출석 구간 — 길이와 양 끝. 없으면 {@link #NONE}(0, null, null).
     * 현재 streak 의 {@code end} 는 오늘 또는 어제(앵커), 최장 기록의 {@code end} 는 그 구간의 마지막 날.
     */
    public record StreakRun(int length, LocalDate start, LocalDate end) {
        public static final StreakRun NONE = new StreakRun(0, null, null);
    }

    /**
     * 오늘(또는 오늘 아직 안 했으면 어제)을 끝으로 하는 연속 출석 일수. 없으면 0.
     */
    public int currentStreak(Long memberId, LocalDate today) {
        return currentStreakRun(memberId, today).length();
    }

    /**
     * {@link #currentStreak} 와 같은 걷기의 결과를 구간으로 — 시작일은 걷기가 멈춘 자리라 추가 조회가 없다
     * (streak-card-api.md §4 «현재 streak 시작일»).
     */
    public StreakRun currentStreakRun(Long memberId, LocalDate today) {
        // 오늘 이후 시각은 보지 않는다 — 미래 start_time 이 들어와도 오늘 기준 streak 에 안 섞이게.
        LocalDateTime cursor = today.plusDays(1).atStartOfDay();
        StreakWalk walk = new StreakWalk(today);

        while (true) {
            List<LocalDateTime> page = sessionRepository.findCompletedStartTimesBefore(
                    memberId, Status.COMPLETED, cursor, PageRequest.of(0, FETCH_BATCH));
            if (page.isEmpty() || walk.consume(page) || page.size() < FETCH_BATCH) {
                return walk.run(); // 더 읽을 게 없거나(빈 페이지·짧은 페이지) 끊김을 만났다
            }
            // 마지막으로 본 시각보다 앞선 것만 다음 페이지로. 같은 시각의 다른 세션이 건너뛰어져도
            // 그 날은 이미 센 날이라 결과가 안 바뀐다(날짜 단위 계산).
            cursor = page.get(page.size() - 1);
        }
    }

    /**
     * 전 기간 최장 연속 출석 구간 — streak-card-api.md §4 후보 A(2026-09-18 confirm): DISTINCT 출석일을
     * 오름차순으로 받아 한 번 훑는다. 현재 streak 과 달리 «답의 크기» 가 아니라 <b>회원의 출석일 수</b>만큼
     * 읽고 나른다 — 정의상 이력 전체를 봐야 해서 창을 둘 수 없고, 저장 컬럼은 세션 삭제에 드리프트한다
     * (#718 과 같은 모양)고 봐서 택하지 않았다. 동률이면 <b>가장 최근</b> 구간(«갱신 중» 판정용).
     * 출석일이 없으면 {@link StreakRun#NONE}.
     */
    public StreakRun longestStreakRun(Long memberId) {
        StreakRun best = StreakRun.NONE;
        LocalDate runStart = null;
        LocalDate prev = null;
        int length = 0;
        for (java.sql.Date sqlDate : sessionRepository.findDistinctDatesByStatus(memberId, Status.COMPLETED)) {
            LocalDate day = sqlDate.toLocalDate();
            if (prev != null && day.equals(prev.plusDays(1))) {
                length++;
            } else {
                runStart = day;
                length = 1;
            }
            if (length >= best.length()) { // >= : 같은 길이면 뒤(최근) 구간이 이긴다
                best = new StreakRun(length, runStart, day);
            }
            prev = day;
        }
        return best;
    }

    /** 기간 안 출석일 집합(양 끝 포함). 스트릭 카드의 «이번 주 7칸» 이 쓴다 — 오늘 여부도 여기서 나온다. */
    public Set<LocalDate> attendedDays(Long memberId, LocalDate from, LocalDate to) {
        Set<LocalDate> days = new HashSet<>();
        for (java.sql.Date sqlDate : sessionRepository.findDistinctActiveDates(
                memberId, List.of(Status.COMPLETED), from.atStartOfDay(), to.atTime(23, 59, 59))) {
            days.add(sqlDate.toLocalDate());
        }
        return days;
    }

    /**
     * 여러 회원의 streak 를 <b>왕복 1회</b>로 — 실험용 후보 b
     * (friend-status-streak-fanout-experiment-design.md §2). 계산 규칙은 {@link #currentStreak} 과
     * 같은 {@link StreakWalk} 이고, 다른 것은 페이지를 회원마다 따로 받느냐 한 결과 집합에서 갈라 쓰느냐뿐이다.
     * 한 페이지({@link #FETCH_BATCH})로 끝나지 않는 회원(31일 이상 연속)은 그 회원만 단건 커서로 이어 걷는다 —
     * 드물고, 그 비용은 «답의 크기 비례» 라는 원래 성질 그대로다.
     *
     * <p>입력 순서대로 키가 들어간 맵을 돌려주고, 세션이 없는 회원은 0.
     */
    public Map<Long, Integer> currentStreaks(Collection<Long> memberIds, LocalDate today) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (memberIds.isEmpty()) {
            return result;
        }
        LocalDateTime before = today.plusDays(1).atStartOfDay();
        Map<Long, List<LocalDateTime>> pages = new HashMap<>();
        for (Object[] row : sessionRepository.findCompletedStartTimesBeforeBatch(
                memberIds, Status.COMPLETED.name(), before, FETCH_BATCH)) {
            pages.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>())
                    .add(((Timestamp) row[1]).toLocalDateTime());
        }
        for (Long memberId : memberIds) {
            List<LocalDateTime> page = pages.getOrDefault(memberId, List.of());
            StreakWalk walk = new StreakWalk(today);
            if (page.isEmpty() || walk.consume(page) || page.size() < FETCH_BATCH) {
                result.put(memberId, walk.run().length());
                continue;
            }
            // 한 페이지가 꽉 찼는데 아직 안 끊겼다 — 이 회원만 단건 커서로 이어 걷는다.
            LocalDateTime cursor = page.get(page.size() - 1);
            while (true) {
                List<LocalDateTime> next = sessionRepository.findCompletedStartTimesBefore(
                        memberId, Status.COMPLETED, cursor, PageRequest.of(0, FETCH_BATCH));
                if (next.isEmpty() || walk.consume(next) || next.size() < FETCH_BATCH) {
                    break;
                }
                cursor = next.get(next.size() - 1);
            }
            result.put(memberId, walk.run().length());
        }
        return result;
    }

    /**
     * 최신순 시작 시각을 받아 연속 일수를 세는 상태 기계 — 단건·배치 두 경로가 같은 규칙을 쓰게 하려고 뺐다.
     * {@link #consume} 은 «끊김을 만나 더 볼 필요가 없다» 면 true.
     */
    private static final class StreakWalk {
        private final LocalDate today;
        private LocalDate anchor;   // 구간의 끝(오늘/어제). null = 아직 못 정함
        private LocalDate expected; // 다음으로 «있어야 하는» 날. null = 아직 앵커(오늘/어제)를 못 정함
        int streak;

        StreakWalk(LocalDate today) {
            this.today = today;
        }

        /** 지금까지 센 구간. 시작일 = 마지막으로 센 날 = {@code expected + 1}. */
        StreakRun run() {
            return streak == 0 ? StreakRun.NONE : new StreakRun(streak, expected.plusDays(1), anchor);
        }

        boolean consume(List<LocalDateTime> newestFirst) {
            for (LocalDateTime startTime : newestFirst) {
                LocalDate day = startTime.toLocalDate();
                if (expected == null) {
                    // 최신 출석일이 오늘도 어제도 아니면 이어지는 streak 이 없다.
                    if (!day.equals(today) && !day.equals(today.minusDays(1))) {
                        return true;
                    }
                    anchor = day;
                    expected = day;
                }
                if (day.isAfter(expected)) {
                    continue; // 같은 날의 다른 세션 — 이미 센 날
                }
                if (day.isBefore(expected)) {
                    return true; // 하루 이상 비었다 — 여기서 끝
                }
                streak++;
                expected = expected.minusDays(1);
            }
            return false;
        }
    }
}
