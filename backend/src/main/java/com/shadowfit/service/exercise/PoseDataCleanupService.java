package com.shadowfit.service.exercise;

import com.shadowfit.repository.exercise.PoseDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * pose_data는 파티셔닝(TTL 만료 시 DROP PARTITION) 적용을 위해 FK(ON DELETE CASCADE)를
 * 제거했다 — MySQL/InnoDB가 FK 걸린 테이블의 파티셔닝을 지원하지 않기 때문
 * (docs/decisions/pose-data-partition-fk-tradeoff.md).
 *
 * 그래서 회원 탈퇴로 세션이 사라져도 pose_data는 더 이상 DB가 자동으로 지워주지 않는다.
 * 이 서비스가 그 대체다. 예전에는 탈퇴 커밋 직후 {@code @Async} 로 한 번 불렀고 실패해도 재시도가 없었다.
 * 지금은 <b>동기</b>이고, 아웃박스 이벤트({@code PURGE_MEMBER_DATA}, {@code MemberPurgeService})가 발행기에서 부른다 —
 * 실패하면 예외가 올라가 발행기가 재시도한다(개인정보보호법 제21조 "지체없이" 파기 요건 대응). 같은 id 로 두 번 불러도 안전하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoseDataCleanupService {

    private final PoseDataRepository poseDataRepository;

    @Transactional
    public void cleanupBySessionIds(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        poseDataRepository.deleteBySessionIdIn(sessionIds);
        log.info("회원 탈퇴에 따른 pose_data 비동기 정리 완료 - 세션 {}개", sessionIds.size());
    }
}
