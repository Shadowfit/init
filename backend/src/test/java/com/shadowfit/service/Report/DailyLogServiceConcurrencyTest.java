package com.shadowfit.service.Report;

import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.DailyLog;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.report.DailyLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DailyLog 첫 기록 INSERT 경합 실측 — 같은 사용자의 그날 첫 두 세션이 정확히 동시에
 * accumulateStats를 호출할 때, catch 블록의 재시도(incrementStats)가 같은 트랜잭션 안에서
 * 실제로 성공하는지(Hibernate 세션이 save() 실패로 손상돼 후속 쿼리가 깨지지 않는지) 검증.
 * 이론(원자 UPDATE라 안전할 것)이 아니라 진짜 두 스레드로 강제 실측.
 */
@SpringBootTest
class DailyLogServiceConcurrencyTest {

    @Autowired private DailyLogService dailyLogService;
    @Autowired private DailyLogRepository dailyLogRepository;
    @Autowired private MemberRepository memberRepository;

    @Test
    @DisplayName("같은 사용자의 그날 첫 두 세션이 동시에 종료돼도 lost-update 없이 합산된다")
    void concurrentFirstAccumulate_bothSucceed_noDataLoss() throws InterruptedException {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("concurrency-test@test.com")
                .username("동시성테스트")
                .password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());
        Long memberId = member.getId();
        LocalDate logDate = LocalDate.of(2026, 7, 15);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                startGate.await();
                dailyLogService.accumulateStats(memberId, logDate, 10, BigDecimal.valueOf(100));
            } catch (Throwable t) {
                errorA.set(t);
            } finally {
                doneGate.countDown();
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                startGate.await();
                dailyLogService.accumulateStats(memberId, logDate, 20, BigDecimal.valueOf(200));
            } catch (Throwable t) {
                errorB.set(t);
            } finally {
                doneGate.countDown();
            }
        });

        threadA.start();
        threadB.start();
        startGate.countDown(); // 두 스레드가 최대한 같은 순간에 accumulateStats를 부르도록
        doneGate.await();

        assertThat(errorA.get()).as("thread A는 예외 없이 끝나야 함").isNull();
        assertThat(errorB.get()).as("thread B는 예외 없이 끝나야 함").isNull();

        List<DailyLog> logs = dailyLogRepository.findAll().stream()
                .filter(l -> l.getMember().getId().equals(memberId) && l.getLogDate().equals(logDate))
                .toList();
        assertThat(logs).as("row가 중복 생성되지 않고 딱 1개여야 함").hasSize(1);

        DailyLog result = logs.get(0);
        assertThat(result.getTotalExerciseTime()).as("10+20 유실 없이 합산돼야 함").isEqualTo(30);
        assertThat(result.getTotalCalories()).as("100+200 유실 없이 합산돼야 함")
                .isEqualByComparingTo(BigDecimal.valueOf(300));
    }
}
