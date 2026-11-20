package com.shadowfit.service.exercise;

import com.shadowfit.repository.exercise.ExercisesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기준 좌표 등록 상태(V34)를 <b>요청 트랜잭션 밖에서</b> 실패로 기록한다 — 2026-10-21 결정 B.
 *
 * <p>영상 추출 요청이 AI 에 접수조차 안 된 경우(파일을 못 찾음·통신 실패·서킷 OPEN)를 남기는 자리다. 이 사건들은
 * {@code ExerciseAnalysisService.extractReferencePoses} 의 gRPC 콜백(이벤트 루프 스레드)이나 업로드 트랜잭션의
 * afterCommit 에서 일어난다. 그래서:
 * <ul>
 *   <li><b>별도 빈</b>이다 — 같은 클래스 안에서 부르면 self-invocation 이라 {@code @Transactional} 이 안 걸린다</li>
 *   <li>{@code REQUIRES_NEW} 다 — afterCommit 안에서는 커밋이 끝난 바깥 트랜잭션의 자원이 아직 묶여 있어,
 *       REQUIRED 로 합류하면 쓴 값이 커밋되지 않고 사라진다(Spring {@code TransactionSynchronization#afterCommit} 주의사항)</li>
 * </ul>
 * AI 가 추출을 끝내고 역호출하는 성공·실패 경로는 여기가 아니라 {@code PoseDataService.saveReferencePoses} 가
 * 기준 좌표 교체와 같은 트랜잭션에서 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceExtractionStatusService {

    private final ExercisesRepository exercisesRepository;

    /** 없는 종목이면 남길 자리가 없어 경고만 한다 — 호출부(gRPC 콜백)는 예외를 받을 사람이 없다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public void markFailed(Long exerciseId, String reason) {
        exercisesRepository.findById(exerciseId).ifPresentOrElse(
                exercise -> {
                    exercise.markReferenceFailed(reason);
                    log.warn("기준 좌표 등록 실패 기록 - 운동 ID: {}, 사유: {}", exerciseId, reason);
                },
                () -> log.warn("기준 좌표 등록 실패를 남길 종목이 없음 - 운동 ID: {}, 사유: {}", exerciseId, reason));
    }
}
