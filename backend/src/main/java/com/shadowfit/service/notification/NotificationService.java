package com.shadowfit.service.notification;

import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.dto.notification.NotificationDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import com.shadowfit.service.member.AdminMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 재촉하기·알림함 (social-cheer-and-group-feed.md §3-C c, §4-1 #6).
 *
 * <p><b>저장이 원천</b>이다. 접속 중인 상대에게 소켓으로 밀어주기(#7)와 푸시(#9)는 이 행이 생긴 뒤에
 * 붙는 전달 수단이고, 이 PR 에는 없다.
 *
 * <p><b>권한</b> (§3-G): 보낸 사람과 받는 사람이 같은 그룹에 둘 다 ACTIVE 여야 한다 — «친구 = 같은 모임
 * 멤버»(§3-A b)라 그 조인 하나가 곧 친구 판정이다. 아니면 403. 자기 자신은 400.
 *
 * <p><b>하루 1회</b> (§3-C 하위 ②): 존재 확인 → 409 가 1차, 그 틈을 뚫은 더블탭은
 * {@code uk_notifications_sender_recipient_type_date} 가 막고 그 위반을 트랜잭션 밖에서 409 로 옮긴다
 * ({@link NotificationWriter}). 서버는 «상대가 오늘 이미 완료했는가» 는 안 본다 — 재촉 버튼 노출은
 * 프론트 규칙({@code MemberAttendanceStatusDto} 주석).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationWriter notificationWriter;
    private final MemberRepository memberRepository;
    private final GroupMemberRepository groupMemberRepository;

    /**
     * 일부러 트랜잭션이 아니다: 검사(읽기)는 각자 돌고, INSERT 만 {@link NotificationWriter} 의 트랜잭션이다.
     * 여기에 {@code @Transactional} 을 붙이면 writer 가 이 트랜잭션에 합류해 UNIQUE 위반이 커밋 시점에
     * 터지고 아래 catch 에 안 걸린다.
     */
    public NotificationDto nudge(Long senderId, Long recipientId, LocalDate today) {
        if (senderId.equals(recipientId)) {
            throw new BusinessException(ErrorCode.NUDGE_SELF_NOT_ALLOWED);
        }
        Member recipient = memberRepository.findById(recipientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!groupMemberRepository.shareGroupWithStatus(senderId, recipientId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }
        if (notificationRepository.existsBySenderIdAndRecipientIdAndTypeAndTargetDate(
                senderId, recipientId, NotificationType.NUDGE, today)) {
            throw new BusinessException(ErrorCode.NUDGE_ALREADY_SENT_TODAY);
        }
        Member sender = memberRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        try {
            return NotificationDto.from(notificationWriter.insert(sender, recipient, NotificationType.NUDGE, today));
        } catch (DataIntegrityViolationException e) {
            // 존재 확인과 INSERT 사이로 들어온 더블탭 — 제약이 막았고, 첫 요청이 이미 재촉을 남겼다.
            throw new BusinessException(ErrorCode.NUDGE_ALREADY_SENT_TODAY);
        }
    }

    /** 알림함 — 내 것만, 최신순. 페이지 크기 기본·상한은 관리자 목록과 같은 값(그쪽 주석의 이유 그대로). */
    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(Long recipientId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? AdminMemberService.DEFAULT_PAGE_SIZE
                : Math.min(size, AdminMemberService.MAX_PAGE_SIZE);
        Page<Notification> found = notificationRepository
                .findAllByRecipientIdOrderByCreatedAtDescIdDesc(recipientId, PageRequest.of(safePage, safeSize));
        return PageResponse.of(found.map(NotificationDto::from).getContent(),
                safePage, safeSize, found.getTotalElements());
    }

    /** 읽음 처리 — 내 것이 아니거나 없으면 404, 이미 읽었으면 그대로 200. */
    @Transactional
    public NotificationDto markRead(Long recipientId, Long notificationId, LocalDateTime now) {
        Notification n = notificationRepository.findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        n.markRead(now);
        return NotificationDto.from(n);
    }
}
