package com.shadowfit.service.exercise;

import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.service.exercise.AiAnalysisClient.PoseRef;
import com.shadowfit.service.exercise.AiAnalysisClient.ReattachCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code ExerciseAnalysisService.reattachSession} 의 1단계 — 재부착 검증 + AI 요청 조립까지의
 * DB 작업 전부를 한 트랜잭션에 가둔다 (이슈 #76). 별도 빈이라 {@code @Transactional} 이 Spring
 * 프록시를 정상적으로 타고, self 주입이 필요 없다 (이슈 #175).
 *
 * <p>이 빈이 반환되는 시점에 트랜잭션이 끝나고 커넥션이 풀로 돌아간다. 호출부
 * {@code ExerciseAnalysisService.reattachSession} 은 그 뒤에 AI 호출을 하므로 외부 지연이
 * 커넥션 점유로 번지지 않는다.
 *
 * <p>🔄 이전엔 proto 타입 {@code ReattachRequest}를 직접 리턴했다. {@link AiAnalysisClient}가
 * 프로토콜 무관 계약이 되면서, 이 빌더도 {@link ReattachCommand}(DTO)를 리턴하도록 바꿨다 —
 * proto ↔ DTO 변환은 이제 {@code GrpcAiAnalysisClient} 안에서만 일어난다
 * (docs/decisions/grpc-webclient-empirical-comparison.md §8).
 */
@Component
@RequiredArgsConstructor
public class ReattachRequestBuilder {

    private final SessionService sessionService;
    // 재부착 시 MAX(rep_number)/MAX(timestamp_sec) 복원 전용 (이슈 #59 2단계)
    private final PoseDataRepository poseDataRepository;
    private final ExerciseReferenceRepository referenceRepository;

    /**
     * <b>lazy 접근을 여기서 끝내야 한다</b> — {@code session.getExercise()}, {@code getMember()} 는
     * 지연 로딩이고 {@code open-in-view: false} 라, 트랜잭션 밖으로 엔티티를 들고 나가면
     * {@code LazyInitializationException} 이 난다. 그래서 엔티티가 아니라 <b>값이 다 채워진</b>
     * {@link ReattachCommand} 를 반환한다.
     */
    @Transactional(readOnly = true)
    public ReattachCommand build(Long sessionId, Long currentMemberId) {
        Session session = sessionService.findReattachableSession(sessionId, currentMemberId);
        return buildFrom(session);
    }

    /**
     * {@link #build(Long, Long)} 의 시스템 트리거 버전 — 회원 소유권 검증 없이 sessionId 만으로
     * 재부착 요청을 조립한다. {@code ExerciseAnalysisService.reattachFromOutbox}(아웃박스 발행기)가
     * 호출한다(#581).
     */
    @Transactional(readOnly = true)
    public ReattachCommand buildById(Long sessionId) {
        Session session = sessionService.findReattachableSessionById(sessionId);
        return buildFrom(session);
    }

    private ReattachCommand buildFrom(Session session) {
        Long sessionId = session.getId();
        Long exerciseId = session.getExercise().getId();

        // 완료된 rep 은 세션 진행 중에 이미 pose_data 로 넘어와 있다(§3-2). AI 메모리가 날아가도
        // 여기서 되찾을 수 있다는 것이 재부착이 성립하는 근거다.
        int restoredRepCount = poseDataRepository.findMaxRepNumberBySessionId(sessionId, session.getStartTime());

        // 시간 축도 rep 축과 똑같이 이어붙여야 한다 (이슈 #156). 값을 pose_data 에서 되읽는 것이
        // 핵심이다 — 저장된 timestamp_sec 자체가 AI 가 «첫 프레임» 을 0 으로 잡아 만든 값이라
        // rep 축과 원점이 같다. session.start_time 기준 경과로 계산하면 «자세 잡는 시간» 이
        // 다시 들어가 틀린다(초판에서 고친 이력 있음).
        double elapsedSec = poseDataRepository.findMaxTimestampSecBySessionId(sessionId, session.getStartTime());

        // AI 는 세션 종료 뒤 상태를 버린다 — 재부착으로 상태를 다시 세울 때 nonce 도 같이
        // 실어야 «검증은 켜졌는데 보관값이 없는» 상태가 안 된다 (#187 (d)). 배포 전에 시작된
        // 세션은 여기가 null 이고, 각 구현체가 자기 프로토콜의 "없음" 표현으로 바꾼다.
        String sessionNonce = session.getSessionNonce();

        // 기준 좌표는 AI 가 보관하지 않는다 — 시작 때와 똑같이 Spring 이 DB 에서 읽어 실어 보낸다.
        List<PoseRef> referencePoses = referenceRepository.findByExerciseId(exerciseId).stream()
                .map(ref -> new PoseRef(ref.getTimestampSec(), ref.getJointCoordinates()))
                .toList();

        return new ReattachCommand(
                sessionId, exerciseId, session.getMember().getSelectedPersona().name(),
                restoredRepCount, elapsedSec, sessionNonce, referencePoses);
    }
}
