package com.shadowfit.service.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.service.exercise.PoseDataCleanupService;
import com.shadowfit.service.group.FeedPhotoStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 탈퇴 후 삭제 이벤트 — 적재(쪼개기)와 처리(멱등·실패 전파). 실제 DB·디스크 없이 협력자를 목으로 두고 이 서비스의 판단만 본다.
 * 실제 삭제가 DB 에서 일어나는지는 {@code PoseDataCleanupServiceTest}, 탈퇴 흐름 전체는 {@code WithdrawalPurgeOutboxIntegrationTest}.
 */
@DisplayName("MemberPurgeService — 탈퇴 후 삭제 이벤트")
class MemberPurgeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxEventRepository outboxRepository;
    private PoseDataCleanupService poseDataCleanupService;
    private FeedPhotoStorage feedPhotoStorage;
    private MemberPurgeService service;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxEventRepository.class);
        poseDataCleanupService = mock(PoseDataCleanupService.class);
        feedPhotoStorage = mock(FeedPhotoStorage.class);
        service = new MemberPurgeService(outboxRepository, poseDataCleanupService, feedPhotoStorage, objectMapper);
    }

    private List<OutboxEvent> enqueued() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private OutboxEvent event(String payload) {
        return OutboxEvent.purgeMemberData(7L, payload, "cid");
    }

    // ── 적재 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 id 가 아주 많아도 모든 페이로드가 컬럼 한도(1000자) 안이고, 순서·내용이 그대로 보존된다")
    void enqueue_manySessions_splitsWithinLimit_preservingAll() throws Exception {
        List<Long> ids = LongStream.rangeClosed(1_000_000L, 1_000_000L + 799).boxed().toList();

        int count = service.enqueue(7L, ids, List.of());

        List<OutboxEvent> saved = enqueued();
        assertThat(count).isEqualTo(saved.size()).isGreaterThan(1);
        List<Long> roundTrip = new ArrayList<>();
        for (OutboxEvent e : saved) {
            assertThat(e.getPayload().length()).as("outbox_events.payload 는 1000자 한도").isLessThanOrEqualTo(1000);
            assertThat(e.getEventType()).isEqualTo(OutboxEventType.PURGE_MEMBER_DATA);
            assertThat(e.getAggregateType()).isEqualTo("MEMBER");
            assertThat(e.getAggregateId()).isEqualTo(7L);
            objectMapper.readTree(e.getPayload()).get("sessionIds").forEach(n -> roundTrip.add(n.asLong()));
        }
        assertThat(roundTrip).containsExactlyElementsOf(ids);
    }

    @Test
    @DisplayName("사진 이름도 같은 방식으로 쪼개고, 세션과 사진은 서로 다른 이벤트로 나간다")
    void enqueue_photosAndSessions_separateEvents() throws Exception {
        List<String> photos = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            photos.add(String.format("%08d-aaaa-bbbb-cccc-dddddddddddd.jpg", i));
        }

        service.enqueue(7L, List.of(1L, 2L), photos);

        List<OutboxEvent> saved = enqueued();
        assertThat(saved).allSatisfy(e -> assertThat(e.getPayload().length()).isLessThanOrEqualTo(1000));
        List<JsonNode> nodes = saved.stream().map(e -> {
            try {
                return objectMapper.readTree(e.getPayload());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).toList();
        assertThat(nodes.stream().filter(n -> n.has("sessionIds")).count()).isEqualTo(1);
        List<String> photoRoundTrip = nodes.stream().filter(n -> n.has("photos"))
                .flatMap(n -> { List<String> l = new ArrayList<>(); n.get("photos").forEach(x -> l.add(x.asText())); return l.stream(); })
                .collect(Collectors.toList());
        assertThat(photoRoundTrip).containsExactlyElementsOf(photos);
        assertThat(nodes.stream().noneMatch(n -> n.has("sessionIds") && n.has("photos"))).isTrue();
    }

    @Test
    @DisplayName("지울 것이 없으면 아무것도 적재하지 않는다")
    void enqueue_nothing_savesNothing() {
        assertThat(service.enqueue(7L, List.of(), List.of())).isZero();
        assertThat(service.enqueue(7L, null, null)).isZero();

        verify(outboxRepository, never()).save(any());
    }

    // ── 처리 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 이벤트는 pose_data 정리를 부르고 SENT 로 끝난다")
    void dispatch_sessions_callsCleanup() {
        DispatchOutcome outcome = service.dispatch(event("{\"sessionIds\":[11,12,13]}"));

        assertThat(outcome).isEqualTo(DispatchOutcome.SENT);
        verify(poseDataCleanupService).cleanupBySessionIds(List.of(11L, 12L, 13L));
    }

    @Test
    @DisplayName("사진 이벤트는 파일마다 삭제를 부른다")
    void dispatch_photos_deletesEach() {
        service.dispatch(event("{\"photos\":[\"a.jpg\",\"b.png\"]}"));

        verify(feedPhotoStorage).deleteOrThrow("a.jpg");
        verify(feedPhotoStorage).deleteOrThrow("b.png");
    }

    @Test
    @DisplayName("삭제가 실패하면 예외가 그대로 올라간다 — 발행기가 RETRY 로 세어 다시 시도한다")
    void dispatch_failure_propagates() {
        doThrow(new IllegalStateException("db down")).when(poseDataCleanupService).cleanupBySessionIds(any());
        doThrow(new UncheckedIOException(new java.io.IOException("disk"))).when(feedPhotoStorage).deleteOrThrow("a.jpg");

        assertThatThrownBy(() -> service.dispatch(event("{\"sessionIds\":[1]}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.dispatch(event("{\"photos\":[\"a.jpg\"]}")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 처리해도 안전하다 — 회수분 재송신(멱등)")
    void dispatch_twice_isSafe() {
        OutboxEvent e = event("{\"sessionIds\":[5]}");

        service.dispatch(e);
        service.dispatch(e);

        verify(poseDataCleanupService, times(2)).cleanupBySessionIds(List.of(5L));
    }

    @Test
    @DisplayName("알아볼 수 없거나 삭제 대상이 없는 페이로드는 조용히 SENT 로 넘기지 않고 예외 — 한도 소진 시 FAILED 로 남아 사람이 본다")
    void dispatch_unreadable_throws() {
        assertThatThrownBy(() -> service.dispatch(event("not json"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dispatch(event("{\"other\":1}"))).isInstanceOf(IllegalArgumentException.class);

        verify(poseDataCleanupService, never()).cleanupBySessionIds(any());
    }

    @Test
    @DisplayName("항목 하나가 한도를 넘으면 쪼갤 수 없다 — 조용히 잘라 넣지 않고 거부")
    void chunk_singleItemTooLong_rejected() {
        assertThatThrownBy(() -> service.chunk("photos", List.of("\"" + "x".repeat(950) + "\"")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
