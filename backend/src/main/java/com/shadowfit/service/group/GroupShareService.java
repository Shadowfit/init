package com.shadowfit.service.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.group.GroupFeedResponseDto;
import com.shadowfit.dto.group.ReactionSummaryDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.ReactionKind;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운동 공유 — 회원이 끝낸 세션을 골라 모임 피드에 올린다(사진 · 한마디 선택).
 *
 * <p><b>중복 방지</b>: 글의 원천을 세션 id 로 두므로 V19 UNIQUE(group_id, event_type, source_id) 가 «같은 운동을
 * 같은 모임에 두 번» 을 막는다. 앞의 exists 는 친절한 409 를 위한 1차 검사이고, 그 틈의 더블탭은 제약이 막는다.
 * 같은 운동을 <b>다른</b> 모임에 올리는 건 된다.
 *
 * <p><b>무엇을 공개하나</b>: 종목 · 유효 횟수 · 운동 시간. 싱크로율 · 관절 좌표는 싣지 않는다 — 출석 외 지표는
 * 비공개가 기본(social-cheer-and-group-feed.md §3-G)이고, 공유는 회원이 직접 고른 행위라 그 셋까지만 연다.
 *
 * <p>일부러 트랜잭션이 아니다: 사진 파일 저장과 글 INSERT 를 묶을 수 없으므로, 글이 실패하면 방금 쓴 파일을 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupShareService {

    private final SessionRepository sessionRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupEventRepository groupEventRepository;
    private final GroupEventService groupEventService;
    private final MemberRepository memberRepository;
    private final FeedPhotoStorage feedPhotoStorage;
    private final ObjectMapper objectMapper;

    public GroupFeedResponseDto.Item share(Long groupId, Long memberId, Long sessionId, String caption, MultipartFile photo) {
        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, memberId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }
        Session session = sessionRepository.findSessionWithExerciseByIdAndMemberId(sessionId, memberId)
                .filter(s -> s.getStatus() == Status.COMPLETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHAREABLE_SESSION_NOT_FOUND));
        if (groupEventRepository.existsByGroupIdAndEventTypeAndSourceId(groupId, GroupEventTypes.SESSION_SHARED, sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_SHARED);
        }

        String photoName = photo == null || photo.isEmpty() ? null : feedPhotoStorage.store(photo);
        String text = caption == null ? null : caption.strip();
        if (text != null && text.length() > 200) {
            text = text.substring(0, 200);
        }

        GroupEvent event;
        try {
            event = groupEventService.publish(groupId, memberId, GroupEventTypes.SESSION_SHARED,
                    payload(session, memberId, text, photoName), sessionId);
        } catch (DataIntegrityViolationException e) {
            feedPhotoStorage.deleteQuietly(photoName);
            throw new BusinessException(ErrorCode.SESSION_ALREADY_SHARED);
        } catch (RuntimeException e) {
            feedPhotoStorage.deleteQuietly(photoName);
            throw e;
        }
        Map<ReactionKind, Long> zero = new EnumMap<>(ReactionKind.class);
        for (ReactionKind k : ReactionKind.values()) {
            zero.put(k, 0L);
        }
        return GroupFeedResponseDto.Item.of(event,
                ReactionSummaryDto.builder().reactions(zero).myReactions(List.of()).build(), List.of());
    }

    /** 이 세션을 이미 올린 내 모임들 — 공유 화면이 그 모임을 «공유됨» 으로 잠근다. */
    @Transactional(readOnly = true)
    public List<Long> sharedGroupIds(Long memberId, Long sessionId) {
        if (sessionRepository.findByIdAndMemberId(sessionId, memberId).isEmpty()) {
            throw new BusinessException(ErrorCode.SHAREABLE_SESSION_NOT_FOUND);
        }
        return groupEventRepository.findGroupIdsByEventTypeAndSourceId(GroupEventTypes.SESSION_SHARED, sessionId);
    }

    private String payload(Session session, Long memberId, String caption, String photoName) {
        // session.getMember() 는 트랜잭션 밖이라 지연 로딩이 안 된다 — 이름은 따로 읽는다.
        String username = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND)).getUsername();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sessionId", session.getId());
        p.put("memberId", memberId);
        p.put("username", username);
        p.put("exerciseName", session.getExercise().getName());
        p.put("totalReps", session.getTotalReps() == null ? 0 : session.getTotalReps());
        p.put("workoutMinutes", session.getEndTime() == null ? 0
                : Duration.between(session.getStartTime(), session.getEndTime()).toMinutes());
        p.put("caption", caption);
        p.put("photoUrl", photoName == null ? null : "/feed-photos/" + photoName);
        try {
            return objectMapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("공유 payload 직렬화 실패", e);
        }
    }
}
