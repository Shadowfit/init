package com.shadowfit.service.exercise;

import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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
     * 오늘(또는 오늘 아직 안 했으면 어제)을 끝으로 하는 연속 출석 일수. 없으면 0.
     */
    public int currentStreak(Long memberId, LocalDate today) {
        // 오늘 이후 시각은 보지 않는다 — 미래 start_time 이 들어와도 오늘 기준 streak 에 안 섞이게.
        LocalDateTime cursor = today.plusDays(1).atStartOfDay();
        LocalDate expected = null; // 다음으로 «있어야 하는» 날. null = 아직 앵커(오늘/어제)를 못 정함
        int streak = 0;

        while (true) {
            List<LocalDateTime> page = sessionRepository.findCompletedStartTimesBefore(
                    memberId, Status.COMPLETED, cursor, PageRequest.of(0, FETCH_BATCH));
            if (page.isEmpty()) {
                return streak;
            }
            for (LocalDateTime startTime : page) {
                LocalDate day = startTime.toLocalDate();
                if (expected == null) {
                    // 최신 출석일이 오늘도 어제도 아니면 이어지는 streak 이 없다.
                    if (!day.equals(today) && !day.equals(today.minusDays(1))) {
                        return 0;
                    }
                    expected = day;
                }
                if (day.isAfter(expected)) {
                    continue; // 같은 날의 다른 세션 — 이미 센 날
                }
                if (day.isBefore(expected)) {
                    return streak; // 하루 이상 비었다 — 여기서 끝
                }
                streak++;
                expected = expected.minusDays(1);
            }
            if (page.size() < FETCH_BATCH) {
                return streak; // 더 읽을 게 없다
            }
            // 마지막으로 본 시각보다 앞선 것만 다음 페이지로. 같은 시각의 다른 세션이 건너뛰어져도
            // 그 날은 이미 센 날이라 결과가 안 바뀐다(날짜 단위 계산).
            cursor = page.get(page.size() - 1);
        }
    }
}
