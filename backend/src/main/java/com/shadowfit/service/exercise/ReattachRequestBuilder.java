package com.shadowfit.service.exercise;

import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.grpc.ReattachRequest;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.RejectedAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ExerciseAnalysisService.reattachSession} 의 1단계 — 재부착 검증 + gRPC 요청 조립까지의
 * DB 작업 전부를 한 트랜잭션에 가둔다 (이슈 #76). 별도 빈이라 {@code @Transactional} 이 Spring
 * 프록시를 정상적으로 타고, self 주입이 필요 없다 (이슈 #175).
 *
 * <p>이 빈이 반환되는 시점에 트랜잭션이 끝나고 커넥션이 풀로 돌아간다. 호출부
 * {@code ExerciseAnalysisService.reattachSession} 은 그 뒤에 gRPC 를 호출하므로 외부 지연이
 * 커넥션 점유로 번지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ReattachRequestBuilder {

    private final SessionService sessionService;
    // 재부착 시 MAX(rep_number)/MAX(timestamp_sec) 복원 전용 (이슈 #59 2단계)
    private final PoseDataRepository poseDataRepository;
    private final ExerciseReferenceRepository referenceRepository;
    // 재부착 시 미인정 시도 수 복원 전용 (V35, 2026-10-21)
    private final RejectedAttemptRepository rejectedAttemptRepository;

    /**
     * <b>lazy 접근을 여기서 끝내야 한다</b> — {@code session.getExercise()}, {@code getMember()} 는
     * 지연 로딩이고 {@code open-in-view: false} 라, 트랜잭션 밖으로 엔티티를 들고 나가면
     * {@code LazyInitializationException} 이 난다. 그래서 엔티티가 아니라 <b>값이 다 채워진</b>
     * {@code ReattachRequest} 를 반환한다.
     */
    @Transactional(readOnly = true)
    public ReattachRequest build(Long sessionId, Long currentMemberId) {
        Session session = sessionService.findReattachableSession(sessionId, currentMemberId);
        return buildFrom(session);
    }

    /**
     * {@link #build(Long, Long)} 의 시스템 트리거 버전 — 회원 소유권 검증 없이 sessionId 만으로
     * 재부착 요청을 조립한다. {@code ExerciseAnalysisService.reattachFromOutbox}(아웃박스 발행기)가
     * 호출한다(#581).
     */
    @Transactional(readOnly = true)
    public ReattachRequest buildById(Long sessionId) {
        Session session = sessionService.findReattachableSessionById(sessionId);
        return buildFrom(session);
    }

    private ReattachRequest buildFrom(Session session) {
        Long sessionId = session.getId();
        Long exerciseId = session.getExercise().getId();

        // 완료된 rep 은 세션 진행 중에 이미 pose_data 로 넘어와 있다(§3-2). AI 메모리가 날아가도
        // 여기서 되찾을 수 있다는 것이 재부착이 성립하는 근거다.
        int restoredRepCount = poseDataRepository.findMaxRepNumberBySessionId(sessionId, session.getStartTime());

        // 미인정 시도(V35)도 rep 축과 같은 이유로 되살린다 — AI 는 미인정 번호(attempt_number, 멱등키)와 화면의 «미인정 N회»
        // 카운터를 메모리에만 두므로, 재부착하면 1 부터 다시 센다. 그러면 새 시도가 이미 저장된 번호와 겹쳐 중복으로 버려진다.
        // 값은 proto 계약대로 «저장된 행 수» 다(화면 카운터가 원하는 것이 «몇 회» 라서). ⚠️ 한 시도가 AI 재전송까지 다 실패해
        // 번호에 구멍이 나 있으면(예: 1·3 저장) 행 수(2)로 이은 다음 번호(3)가 기존 행과 겹쳐 그 시도가 중복으로 버려진다 —
        // 유실이 이미 난 세션에서 한 건 더 잃는 경우라 감수한다.
        int restoredRejectedCount = Math.toIntExact(rejectedAttemptRepository.countBySessionId(sessionId));

        // 시간 축도 rep 축과 똑같이 이어붙여야 한다 (이슈 #156). AI 는 프레임 시각을 «첫 프레임
        // 도착부터의 경과» 로 만드는데, 재부착으로 상태를 새로 만들면 그 기준이 재부착 시점이 되어
        // 이후 프레임의 timestamp_sec 이 0 부터 다시 시작한다. 그러면 리포트의 «최악 구간 시각» 이
        // 세션 앞부분과 겹치는 값으로 표시된다 — 여기서 이미 흐른 시간을 실어 보내 메운다.
        //
        // 값을 **pose_data 에서 되읽는** 것이 핵심이다. 바로 위 rep 축과 같은 데이터원이고, 그래서
        // 원점도 같다 — 저장된 timestamp_sec 자체가 AI 가 «첫 프레임» 을 0 으로 잡아 만든 값이다.
        //
        // session.start_time 기준 경과로 계산하면 안 된다(초판이 그렇게 했다가 고쳤다). 그건 원점이
        // «세션 생성» 이라, AI 가 «운동 시간이 아니다» 라며 의도적으로 뺀 자세 잡는 시간이 다시
        // 들어간다. 준비에 20초 걸린 세션이면 재부착 이후 시각이 통째로 20초 앞서 표시된다.
        double elapsedSec = poseDataRepository.findMaxTimestampSecBySessionId(sessionId, session.getStartTime());

        // AI 는 세션 종료 뒤 상태를 버린다 — 재부착으로 상태를 다시 세울 때 nonce 도 같이
        // 실어야 «검증은 켜졌는데 보관값이 없는» 상태가 안 된다 (#187 (d)).
        // 배포 전에 시작된 세션은 여기가 null 이고, 빈 문자열로 나가 compat 통과가 된다.
        String sessionNonce = session.getSessionNonce();

        // 시작 때와 같은 값을 다시 실어준다(② — AnalyzeRequest 7·8·9 와 동일). AI 는 initial_rep_count 와
        // target_reps_per_set 로 «몇 세트째 몇 번째 rep» 을 역산해 세트 cue 를 잇는다(핸드오프 §2-3).
        ExerciseAnalysisService.SessionTargets targets = ExerciseAnalysisService.SessionTargets.of(session);

        ReattachRequest.Builder requestBuilder = ReattachRequest.newBuilder()
                .setSessionId(sessionId)
                .setExerciseId(exerciseId)
                .setPersona(session.getMember().getSelectedPersona().name())
                .setInitialRepCount(restoredRepCount)
                .setInitialRejectedCount(restoredRejectedCount)
                .setElapsedSec(elapsedSec)
                .setSessionNonce(sessionNonce == null ? "" : sessionNonce)
                .setExerciseCode(targets.exerciseCode())
                .setTargetRepsPerSet(targets.targetRepsPerSet())
                .setTargetSets(targets.targetSets())
                // AnalyzeRequest.depth_mode(10) 와 같은 규약 — 시작 때와 같은 깊이 모드를 다시 실어준다.
                .setDepthMode(session.getSquatDepthMode().name())
                // 판정 기준 한 쌍(V34, proto 12·13) — AnalyzeRequest 11·12 와 같다. 종목의 «지금» 값이라 그사이
                // 관리자가 바꿨으면 재부착 이후는 새 기준을 쓴다(앱도 재부착 응답으로 같은 값을 받는다).
                .setSyncGoodThreshold(targets.syncGoodThreshold())
                .setSyncWarnThreshold(targets.syncWarnThreshold());

        // 기준 좌표는 AI 가 보관하지 않는다 — 시작 때와 똑같이 Spring 이 DB 에서 읽어 실어 보낸다.
        for (ExerciseReference ref : referenceRepository.findByExerciseId(exerciseId)) {
            requestBuilder.addReferencePoses(PoseDataRequest.newBuilder()
                    .setTimestampSec(ref.getTimestampSec())
                    .setJointCoordinates(ref.getJointCoordinates())
                    .build());
        }

        return requestBuilder.build();
    }
}
