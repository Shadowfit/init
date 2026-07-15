package com.shadowfit.service.Report;

import com.shadowfit.dto.report.record.DailyLogRequestDto;
import com.shadowfit.dto.report.record.DailyLogResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.report.DailyLog;
import com.shadowfit.repository.report.DailyLogRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyLogService {
    private final DailyLogRepository dailyLogRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public void saveOrUpdateLog(Long memberId, DailyLogRequestDto dto) {
        log.info("일지 저장 요청 - 사용자: {}, 날짜: {}", memberId, dto.getLogDate());

        Optional<DailyLog> existing = dailyLogRepository.findByMemberIdAndLogDate(memberId, dto.getLogDate());

        if (existing.isPresent()) {
            // 영속성 컨텍스트가 관리 중인 엔티티 — 필드 수정만 하면 트랜잭션 커밋 시 더티체킹으로 자동 UPDATE
            DailyLog log = existing.get();
            log.setMemo(dto.getMemo());
            log.setMood(dto.getMood());
        } else {
            // 새 엔티티는 영속성 컨텍스트에 없으므로 save()로 등록해야 INSERT
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            dailyLogRepository.save(DailyLog.builder()
                    .member(member)
                    .logDate(dto.getLogDate())
                    .memo(dto.getMemo())
                    .mood(dto.getMood())
                    .build());
        }
    }
    /**
     * 세션 종료(완료) 시 그날의 누적 운동시간·칼로리에 반영. INSERT-또는-누적 판단과 실제 누적을
     * DB 네이티브 upsert 한 문장(ON DUPLICATE KEY UPDATE)으로 처리 — 같은 날 두 세션이 동시에
     * 종료돼도 lost-update가 안 생김. JPA save()로 먼저 시도하고 실패하면 catch해서 재시도하는
     * 방식은 Hibernate 세션이 flush 실패로 손상돼 같은 트랜잭션 내 후속 쿼리가 깨짐(실측으로 확인,
     * DailyLogServiceConcurrencyTest 최초 버전 실패 사유) — 그래서 이 방식은 쓰지 않음.
     */
    @Transactional
    public void accumulateStats(Long memberId, LocalDate logDate, int addTime, BigDecimal addCalories) {
        dailyLogRepository.upsertStats(memberId, logDate, addTime, addCalories);
    }

    @Transactional(readOnly = true)
    public DailyLogResponseDto getDailyLog(Long memberId, LocalDate date) {
        DailyLog log = dailyLogRepository.findByMemberIdAndLogDate(memberId, date)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        // 엔티티를 응답 DTO로 변환하여 반환
        return new DailyLogResponseDto(log);
    }
}
