package com.shadowfit.service.exercise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.CallCancellation;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.RejectedAttemptRequest;
import com.shadowfit.model.exercise.RejectedAttemptReason;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.RejectedAttemptRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * [AI → Spring] «미인정 시도» 1건 저장 (V35, proto {@code SaveRejectedAttempt}, 2026-10-21).
 *
 * <p>내려갔다 올라왔지만 AI 가 1회로 인정하지 않은 시도다. 횟수·세트에는 안 들어가고 점수·리포트에만 들어간다
 * ({@code SessionCompletionTx.resolveSyncStats}, 리포트 precompute). 판정 기준은 그대로 엄격하게 두고 «미달 시도가
 * 안 보이는» 구멍만 막는다 — {@code docs/decisions/counted-reps-vs-scored-attempts.md}.
 *
 * <p><b>세션 상태 규칙은 {@code PoseDataService.savePoseDataBatch} 와 같다</b>:
 * <ul>
 *   <li>세션이 없으면 {@code SESSION_NOT_FOUND}(→ gRPC NOT_FOUND) — AI 가 재전송하지 않는다</li>
 *   <li>{@code IN_PROGRESS} 가 아니면(완료·실패·취소 뒤 도착) <b>조용히 버리고 성공으로 답한다</b> — 던지면 AI 가 영영
 *       거절될 요청을 재전송한다. 사용자가 종료를 눌러도 status 는 AI 완료 콜백 전까지 IN_PROGRESS 라, 종료 직전의
 *       정상 시도는 통과한다(pose 배치와 같은 유예)</li>
 * </ul>
 * 횟수만 세는 종목({@link ExerciseAnalysisCapabilities#isCountOnly})도 버린다 — 점수가 없는 종목이라 «미인정 시도의
 * 점수» 가 성립하지 않고, {@code sync_rate} 가 NOT NULL 이라 저장하면 가짜 값이 남는다.
 *
 * <p><b>멱등</b>: {@code (session_id, attempt_no)} UNIQUE + {@code ON DUPLICATE KEY UPDATE id = id}. 이미 있으면 먼저
 * 걸러 «중복» 으로 세고, 동시에 두 번 오는 경합은 ODKU 가 흡수한다. {@code INSERT IGNORE} 가 아닌 이유는
 * {@code FeedbackLogService} 와 같다(#219 — IGNORE 는 FK·NOT NULL 위반까지 삼킨다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RejectedAttemptService {

    /** 세션 경과 상한 — {@code PoseDataValidationGate.MAX_TIMESTAMP_SEC} 와 같은 사니티 가드(60분). */
    private static final double MAX_TIMESTAMP_SEC = 3600.0;

    private static final String INSERT_SQL =
            "INSERT INTO exercise_rejected_attempts " +
            "(session_id, attempt_no, reason, sync_rate, min_knee_angle, torso_tilt_deg, timestamp_sec, joint_coordinates, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE id = id";

    private final SessionRepository sessionRepository;
    private final RejectedAttemptRepository rejectedAttemptRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SessionMetrics sessionMetrics;
    private final ObjectMapper objectMapper;

    /**
     * @return 새로 저장했으면 {@code true}. 중복(재전송)·종료된 세션·점수 없는 종목이라 저장하지 않았으면 {@code false}
     *         — 어느 쪽이든 AI 에는 성공으로 답한다(재전송할 일이 아니다).
     * @throws BusinessException 형식·범위 위반({@code INVALID_INPUT_VALUE}/{@code DATA_INTEGRITY_VIOLATION} → INVALID_ARGUMENT),
     *                           세션 없음({@code SESSION_NOT_FOUND} → NOT_FOUND)
     */
    @Transactional
    public boolean save(RejectedAttemptRequest request) {
        long sessionId = request.getSessionId();
        // 형식·범위 검증이 세션 조회보다 먼저다 — 어차피 거절할 요청이면 DB 를 만질 이유가 없다(PoseDataValidationGate 와 같은 순서).
        RejectedAttemptReason reason = validate(request);
        Double minKneeAngle = request.getMinKneeAngle() == 0.0 ? null : request.getMinKneeAngle();
        String jointCoordinates = request.getJointCoordinates().isBlank() ? null : request.getJointCoordinates();

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (session.getStatus() != Status.IN_PROGRESS) {
            sessionMetrics.rejectedAttempt("dropped");
            log.warn("세션 {} : {} 상태에 도착한 미인정 시도 #{} 를 버린다 (pose 배치와 같은 규칙, #187 (b))",
                    sessionId, session.getStatus(), request.getAttemptNumber());
            return false;
        }
        if (ExerciseAnalysisCapabilities.isCountOnly(session)) {
            sessionMetrics.rejectedAttempt("dropped");
            log.warn("세션 {} : 횟수만 세는 종목이라 미인정 시도 #{} 를 저장하지 않는다 — 점수가 없는 종목이다",
                    sessionId, request.getAttemptNumber());
            return false;
        }
        if (rejectedAttemptRepository.existsBySessionIdAndAttemptNo(sessionId, request.getAttemptNumber())) {
            sessionMetrics.rejectedAttempt("duplicate");
            log.info("세션 {} : 미인정 시도 #{} 재전송 — 이미 저장돼 있어 그대로 둔다", sessionId, request.getAttemptNumber());
            return false;
        }

        // 되돌릴 수 없는 쓰기 직전 — 그 사이 호출자가 포기했으면 시작하지 않는다(#206-B, PoseDataService 와 같은 자리).
        CallCancellation.abortIfAbandoned("미인정 시도 저장 (session=" + sessionId + ")");

        // 활동 시각 갱신 — 미인정 시도도 «사용자가 아직 운동 중» 이라는 신호다. 얕은 시도만 반복하는 동안 pose 배치가
        // 안 와도 유휴 타임아웃(Session.timeoutThreshold)이 세션을 걷어가면 안 된다. JPA 가 아니라 직접 UPDATE 하는
        // 이유는 PoseDataService 와 같다(@Version 을 올리지 않는다).
        //
        // 🔴 INSERT 보다 <b>먼저</b> 한다 — 부모(세션) 행을 X 로 먼저 잡아야 데드락이 안 난다. 이 표는 FK 가 있어 INSERT 가
        //    부모 행에 S 잠금을 건다. 순서가 «INSERT → UPDATE» 면 같은 시도의 동시 재전송 둘이 서로의 S 잠금 때문에
        //    UPDATE(X)를 못 얻어 데드락이 난다(실 MySQL 에서 재현 — RejectedAttemptRaceTest). 부모를 먼저 잡으면 둘이
        //    줄을 서고, 뒤엣것은 UNIQUE 에서 no-op 이 된다.
        // status 조건을 같이 거는 이유: 위 상태 확인(잠금 없는 읽기)과 여기 사이에 완료 콜백이 커밋됐으면 0행이 되어,
        //    이미 확정된 리포트 뒤에 시도가 붙지 않는다.
        LocalDateTime now = LocalDateTime.now();
        int touched = jdbcTemplate.update(
                "UPDATE exercise_sessions SET last_active_at = ? WHERE id = ? AND status = 'IN_PROGRESS'", now, sessionId);
        if (touched == 0) {
            sessionMetrics.rejectedAttempt("dropped");
            log.warn("세션 {} : 미인정 시도 #{} 저장 직전에 세션이 끝났다 — 버린다", sessionId, request.getAttemptNumber());
            return false;
        }

        jdbcTemplate.update(INSERT_SQL,
                sessionId,
                request.getAttemptNumber(),
                reason.name(),
                request.getSyncRate(),
                minKneeAngle,
                request.getTorsoTiltDeg(),
                request.getTimestampSec(),
                jointCoordinates,
                now);

        sessionMetrics.rejectedAttempt("stored");
        log.info("세션 {} : 미인정 시도 #{} 저장 (사유 {}, 싱크로율 {}, 무릎 {})",
                sessionId, request.getAttemptNumber(), reason, request.getSyncRate(), minKneeAngle);
        return true;
    }

    /**
     * 형식·범위 검증. 통과하면 파싱한 사유를 돌려준다.
     *
     * <p>{@code attempt_number} 0 을 거절하는 것은 형식 검사가 아니라 유실 방어다 — proto3 는 «미설정» 을 0 으로 보내고,
     * 그대로 받으면 그 세션의 모든 시도가 «0번» 에서 서로를 중복으로 지운다({@code FeedbackLogService} 의 rep_number 와 같은 이유).
     * {@code min_knee_angle} 의 0 은 «미상» 이라 거절하지 않고 NULL 로 접는다(스쿼트 무릎각은 0 이 될 수 없다).
     */
    private RejectedAttemptReason validate(RejectedAttemptRequest request) {
        long sessionId = request.getSessionId();
        if (request.getAttemptNumber() <= 0) {
            throw invalid(sessionId, ErrorCode.INVALID_INPUT_VALUE,
                    "attempt_number 가 비었다(=" + request.getAttemptNumber() + ") — 보내는 쪽이 필드를 안 채웠을 가능성이 크다");
        }
        RejectedAttemptReason reason;
        try {
            reason = RejectedAttemptReason.valueOf(request.getReason());
        } catch (IllegalArgumentException e) {
            throw invalid(sessionId, ErrorCode.INVALID_INPUT_VALUE, "알 수 없는 사유 '" + request.getReason() + "'");
        }
        if (!inRange(request.getSyncRate(), 0.0, 100.0)) {
            throw invalid(sessionId, ErrorCode.DATA_INTEGRITY_VIOLATION, "sync_rate=" + request.getSyncRate() + " 범위 [0,100] 밖");
        }
        if (!inRange(request.getTimestampSec(), 0.0, MAX_TIMESTAMP_SEC)) {
            throw invalid(sessionId, ErrorCode.DATA_INTEGRITY_VIOLATION,
                    "timestamp_sec=" + request.getTimestampSec() + " 범위 [0," + MAX_TIMESTAMP_SEC + "] 밖");
        }
        if (!inRange(request.getMinKneeAngle(), 0.0, 180.0)) {
            throw invalid(sessionId, ErrorCode.DATA_INTEGRITY_VIOLATION, "min_knee_angle=" + request.getMinKneeAngle() + " 범위 [0,180] 밖");
        }
        if (!inRange(request.getTorsoTiltDeg(), 0.0, 180.0)) {
            throw invalid(sessionId, ErrorCode.DATA_INTEGRITY_VIOLATION, "torso_tilt_deg=" + request.getTorsoTiltDeg() + " 범위 [0,180] 밖");
        }
        // 컬럼이 JSON 이라 깨진 문자열은 MySQL 이 INSERT 에서 거절한다 — 그건 «우리가 아프다»(INTERNAL)로 보여 AI 가 재전송한다.
        // 보내는 쪽 잘못이므로 입구에서 INVALID_ARGUMENT 로 가른다.
        String joints = request.getJointCoordinates();
        if (!joints.isBlank()) {
            try {
                objectMapper.readTree(joints);
            } catch (Exception e) {
                throw invalid(sessionId, ErrorCode.DATA_INTEGRITY_VIOLATION, "joint_coordinates 가 JSON 이 아니다");
            }
        }
        return reason;
    }

    private static boolean inRange(double value, double min, double max) {
        return !Double.isNaN(value) && value >= min && value <= max;
    }

    private BusinessException invalid(long sessionId, ErrorCode code, String detail) {
        sessionMetrics.rejectedAttempt("invalid");
        log.warn("세션 {} : 미인정 시도 거부 — {}", sessionId, detail);
        return new BusinessException(code);
    }
}
