package com.shadowfit.service.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.service.exercise.PoseDataCleanupService;
import com.shadowfit.service.group.FeedPhotoStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 회원 탈퇴 뒤 <b>DB 가 자동으로 못 지우는 것</b>(pose_data 좌표, 피드 사진 파일)을 아웃박스로 지운다.
 *
 * <p>예전에는 탈퇴 트랜잭션의 {@code afterCommit} 에서 {@code @Async} 로 한 번 지웠다. 그 호출이 실패하면 재시도가 없었고,
 * 최종 안전망은 월 파티션 TTL(최대 약 2개월)이라 «지체 없이 파기» 요건에 약했다. 이제는 탈퇴 트랜잭션 <b>안에서</b> 삭제
 * 이벤트를 같은 커밋으로 적재한다 — 탈퇴가 롤백되면 이벤트도 사라지고, 커밋되면 발행기가 지울 때까지 재시도한다
 * (백오프, 한도 소진 시 FAILED → {@code shadowfit.outbox.failed} 게이지로 보인다).
 *
 * <p>삭제는 <b>멱등</b>이다 — 같은 이벤트가 두 번 와도(회수분 재송신) 결과가 같다. 지운 행·없는 파일은 «이미 끝남» 이다.
 *
 * <p>페이로드는 {@code outbox_events.payload}(1000자)에 맞춰 <b>여러 이벤트로 쪼갠다</b> — 세션이 많은 회원의 id 목록이 한 행에
 * 안 들어가기 때문이다. 한 이벤트는 {@code {"sessionIds":[…]}} 또는 {@code {"photos":["이름",…]}} 중 하나다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberPurgeService {

    /** 컬럼 한도는 1000자 — 여유를 두고 900 에서 끊는다(JSON 틀·구분자 포함 길이로 센다). */
    static final int MAX_PAYLOAD_CHARS = 900;

    private final OutboxEventRepository outboxRepository;
    private final PoseDataCleanupService poseDataCleanupService;
    private final FeedPhotoStorage feedPhotoStorage;
    private final ObjectMapper objectMapper;

    /**
     * 삭제 이벤트를 적재한다. 탈퇴 트랜잭션 안에서만 부른다(MANDATORY) — 밖에서 부르면 «탈퇴는 롤백됐는데 삭제는 나가는»
     * 일이 생긴다.
     *
     * @return 적재한 이벤트 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueue(Long memberId, List<Long> sessionIds, List<String> photoNames) {
        List<String> payloads = new ArrayList<>();
        if (sessionIds != null && !sessionIds.isEmpty()) {
            payloads.addAll(chunk("sessionIds", sessionIds.stream().map(String::valueOf).toList()));
        }
        if (photoNames != null && !photoNames.isEmpty()) {
            payloads.addAll(chunk("photos", photoNames.stream().map(this::quote).toList()));
        }
        String cid = CorrelationIds.current();
        for (String payload : payloads) {
            outboxRepository.save(OutboxEvent.purgeMemberData(memberId, payload, cid));
        }
        if (!payloads.isEmpty()) {
            log.info("탈퇴 후 삭제 이벤트 적재 — memberId={}, 이벤트 {}건", memberId, payloads.size());
        }
        return payloads.size();
    }

    /**
     * 삭제 이벤트 한 건을 처리한다 — 발행기가 트랜잭션 <b>밖에서</b> 부른다. 실패는 예외로 올리면 발행기가 RETRY 로 센다.
     * 알아볼 수 없는 페이로드도 예외다 — 재시도 한도를 넘기면 FAILED 로 남아 사람이 본다(조용히 SENT 로 넘기지 않는다).
     */
    public DispatchOutcome dispatch(OutboxEvent event) {
        JsonNode node;
        try {
            node = objectMapper.readTree(event.getPayload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("삭제 이벤트 페이로드를 읽을 수 없다 (id=" + event.getId() + ")", e);
        }

        boolean handled = false;
        if (node.has("sessionIds")) {
            List<Long> ids = new ArrayList<>();
            node.get("sessionIds").forEach(n -> ids.add(n.asLong()));
            poseDataCleanupService.cleanupBySessionIds(ids);   // 동기 — 실패하면 예외 → 발행기가 RETRY
            handled = true;
        }
        if (node.has("photos")) {
            for (JsonNode n : node.get("photos")) {
                feedPhotoStorage.deleteOrThrow(n.asText());     // 없는 파일은 성공, 지우다 I/O 가 실패하면 예외
            }
            handled = true;
        }
        if (!handled) {
            throw new IllegalArgumentException("삭제 대상이 없는 이벤트 (id=" + event.getId() + ")");
        }
        return DispatchOutcome.SENT;
    }

    /** JSON 조각 목록을 {@code {"field":[…]}} 가 {@link #MAX_PAYLOAD_CHARS} 를 넘지 않게 묶는다. 순서·내용은 보존한다. */
    List<String> chunk(String field, List<String> jsonItems) {
        String head = "{\"" + field + "\":[";
        String tail = "]}";
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String item : jsonItems) {
            if (head.length() + item.length() + tail.length() > MAX_PAYLOAD_CHARS) {
                throw new IllegalArgumentException("항목 하나가 페이로드 한도를 넘는다: " + item.length() + "자");
            }
            int add = (cur.length() == 0 ? 0 : 1) + item.length();
            if (cur.length() > 0 && head.length() + cur.length() + add + tail.length() > MAX_PAYLOAD_CHARS) {
                out.add(head + cur + tail);
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(',');
            }
            cur.append(item);
        }
        if (cur.length() > 0) {
            out.add(head + cur + tail);
        }
        return out;
    }

    private String quote(String s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
