package com.shadowfit.service.notification;

import com.shadowfit.dto.notification.NotificationListResponseDto;
import com.shadowfit.dto.notification.NotificationResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 알림 저장·조회·읽음 — 재촉하기(social-cheer-and-group-feed.md §3-C c, §4-2)의 저장 원천.
 *
 * <p><b>권한</b> (§3-G a): 같은 ACTIVE 그룹에 둘 다 있으면 재촉할 수 있다. 아니면 403(G002).
 *
 * <p><b>남발 방지</b> (§3-C 하위 ②): 같은 사람에게 같은 날 같은 종류 1회. 사전 exists 검사로 409 를 내고,
 * 더블탭처럼 두 요청이 검사를 동시에 통과한 경우는 {@code uk_notifications_daily} 위반을 잡아 같은 409 로
 * 낸다 — 사전 검사만 두면 경합의 두 번째가 500 이 된다.
 *
 * <p><b>서버는 «오늘 이미 완료한 대상» 재촉을 막지 않는다</b> (§4-2 ④) — 버튼 노출은 프론트 규칙
 * ({@code !attendedToday}). 서버 규칙은 남발 방지 하나로 좁게 둔다.
 *
 * <p><b>전달</b>: 이 클래스는 행만 쓴다. 푸시(#9, 아웃박스)·개인 소켓(#7)은 이 행을 나르는 별도 층이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final MemberRepository memberRepository;

    /** 재촉 1건 저장. {@code today} 는 서버 LocalDate — AttendanceService 의 «오늘» 과 같은 시계여야 한다(§4-2 ③). */
    @Transactional
    public NotificationResponseDto nudge(Long senderId, Long recipientId, LocalDate today) {
        if (senderId.equals(recipientId)) {
            throw new BusinessException(ErrorCode.NUDGE_SELF);
        }
        Member recipient = memberRepository.findById(recipientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (groupMemberRepository.countSharedGroups(senderId, recipientId, GroupMemberStatus.ACTIVE) == 0) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }
        if (notificationRepository.existsBySenderIdAndRecipientIdAndTypeAndTargetDate(
                senderId, recipientId, NotificationType.NUDGE, today)) {
            throw new BusinessException(ErrorCode.NUDGE_ALREADY_SENT_TODAY);
        }
        Member sender = memberRepository.getReferenceById(senderId);
        Notification notification = Notification.builder()
                .recipient(recipient).sender(sender).type(NotificationType.NUDGE).targetDate(today).build();
        try {
            // flush 를 여기서 해야 UNIQUE 위반이 커밋 시점이 아니라 이 try 안에서 난다.
            notification = notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.NUDGE_ALREADY_SENT_TODAY);
        }
        // 응답의 sender 닉네임·프로필은 getReferenceById 프록시라 여기서 초기화된다(같은 트랜잭션 안).
        return NotificationResponseDto.from(notification);
    }

    /** 받은 알림 최신순 keyset. size 는 1..{@value MAX_PAGE_SIZE}, 없으면 {@value DEFAULT_PAGE_SIZE}. */
    public NotificationListResponseDto list(Long recipientId, Long before, Integer size) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        // 한 건 더 읽어 다음 장 유무를 판정한다 — COUNT 쿼리 없이.
        List<Notification> rows = notificationRepository.findPageByRecipient(recipientId, before, PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<Notification> page = hasNext ? rows.subList(0, pageSize) : rows;
        return NotificationListResponseDto.builder()
                .items(page.stream().map(NotificationResponseDto::from).toList())
                .hasNext(hasNext)
                .nextCursor(hasNext ? page.get(page.size() - 1).getId() : null)
                .build();
    }

    public long unreadCount(Long recipientId) {
        return notificationRepository.countByRecipientIdAndReadAtIsNull(recipientId);
    }

    /** 건별 읽음 — 남의 알림은 존재 여부를 흘리지 않도록 404 로 같이 닫는다. 이미 읽은 건 그대로. */
    @Transactional
    public NotificationResponseDto markRead(Long recipientId, Long notificationId, LocalDateTime now) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead(now);
        return NotificationResponseDto.from(notification);
    }

    /** 모두 읽음 — UPDATE 한 문장. 반환은 이번에 바뀐 건수. */
    @Transactional
    public int markAllRead(Long recipientId, LocalDateTime now) {
        return notificationRepository.markAllRead(recipientId, now);
    }
}
