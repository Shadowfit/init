package com.shadowfit.service.notification;

import com.shadowfit.dto.notification.NotificationDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotificationService 테스트")
class NotificationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationWriter notificationWriter;
    @Mock private MemberRepository memberRepository;
    @Mock private GroupMemberRepository groupMemberRepository;

    private NotificationService service;
    private Member me, friend;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationService(notificationRepository, notificationWriter, memberRepository, groupMemberRepository);
        me = member(1L, "me");
        friend = member(2L, "friend");
        when(memberRepository.findById(1L)).thenReturn(Optional.of(me));
        when(memberRepository.findById(2L)).thenReturn(Optional.of(friend));
    }

    @Test
    @DisplayName("nudge — 같은 모임 ACTIVE 이고 오늘 처음이면 저장하고 DTO 를 돌려준다")
    void nudge_happyPath() {
        when(groupMemberRepository.shareGroupWithStatus(1L, 2L, GroupMemberStatus.ACTIVE)).thenReturn(true);
        when(notificationRepository.existsBySenderIdAndRecipientIdAndTypeAndTargetDate(1L, 2L, NotificationType.NUDGE, TODAY))
                .thenReturn(false);
        when(notificationWriter.insert(me, friend, NotificationType.NUDGE, TODAY))
                .thenReturn(notification(10L, me, friend));

        NotificationDto dto = service.nudge(1L, 2L, TODAY);

        assertThat(dto.getId()).isEqualTo(10L);
        assertThat(dto.getType()).isEqualTo(NotificationType.NUDGE);
        assertThat(dto.getSenderId()).isEqualTo(1L);
        assertThat(dto.getSenderUsername()).isEqualTo("me");
        assertThat(dto.isRead()).isFalse();
    }

    @Test
    @DisplayName("nudge — 자기 자신은 NUDGE_SELF_NOT_ALLOWED, 다른 검사에 가지도 않는다")
    void nudge_self_rejected() {
        assertCode(() -> service.nudge(1L, 1L, TODAY), ErrorCode.NUDGE_SELF_NOT_ALLOWED);
        verify(groupMemberRepository, never()).shareGroupWithStatus(any(), any(), any());
    }

    @Test
    @DisplayName("nudge — 없는 회원은 USER_NOT_FOUND (권한 검사보다 먼저)")
    void nudge_unknownRecipient() {
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());
        assertCode(() -> service.nudge(1L, 99L, TODAY), ErrorCode.USER_NOT_FOUND);
        verify(groupMemberRepository, never()).shareGroupWithStatus(any(), any(), any());
    }

    @Test
    @DisplayName("nudge — 같은 모임 ACTIVE 가 아니면 NOT_GROUP_MEMBER, 저장 안 함")
    void nudge_notSameGroup() {
        when(groupMemberRepository.shareGroupWithStatus(1L, 2L, GroupMemberStatus.ACTIVE)).thenReturn(false);
        assertCode(() -> service.nudge(1L, 2L, TODAY), ErrorCode.NOT_GROUP_MEMBER);
        verify(notificationWriter, never()).insert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("nudge — 오늘 이미 보냈으면 NUDGE_ALREADY_SENT_TODAY (존재 확인 단계)")
    void nudge_alreadySentToday_preCheck() {
        when(groupMemberRepository.shareGroupWithStatus(1L, 2L, GroupMemberStatus.ACTIVE)).thenReturn(true);
        when(notificationRepository.existsBySenderIdAndRecipientIdAndTypeAndTargetDate(1L, 2L, NotificationType.NUDGE, TODAY))
                .thenReturn(true);
        assertCode(() -> service.nudge(1L, 2L, TODAY), ErrorCode.NUDGE_ALREADY_SENT_TODAY);
        verify(notificationWriter, never()).insert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("nudge — 존재 확인을 뚫은 더블탭은 UNIQUE 위반을 409 로 옮긴다")
    void nudge_doubleTap_uniqueViolationBecomes409() {
        when(groupMemberRepository.shareGroupWithStatus(1L, 2L, GroupMemberStatus.ACTIVE)).thenReturn(true);
        when(notificationRepository.existsBySenderIdAndRecipientIdAndTypeAndTargetDate(1L, 2L, NotificationType.NUDGE, TODAY))
                .thenReturn(false);
        when(notificationWriter.insert(me, friend, NotificationType.NUDGE, TODAY))
                .thenThrow(new DataIntegrityViolationException("uk_notifications_sender_recipient_type_date"));

        assertCode(() -> service.nudge(1L, 2L, TODAY), ErrorCode.NUDGE_ALREADY_SENT_TODAY);
    }

    @Test
    @DisplayName("markRead — 내 것이 아니거나 없으면 NOTIFICATION_NOT_FOUND")
    void markRead_notMine() {
        when(notificationRepository.findByIdAndRecipientId(10L, 2L)).thenReturn(Optional.empty());
        assertCode(() -> service.markRead(2L, 10L, LocalDateTime.now()), ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("markRead — 처음 읽은 시각을 유지한다 (두 번 눌러도 같은 답)")
    void markRead_idempotent() {
        Notification n = notification(10L, me, friend);
        when(notificationRepository.findByIdAndRecipientId(10L, 2L)).thenReturn(Optional.of(n));
        LocalDateTime first = LocalDateTime.of(2026, 9, 12, 10, 0);

        NotificationDto a = service.markRead(2L, 10L, first);
        NotificationDto b = service.markRead(2L, 10L, first.plusHours(1));

        assertThat(a.isRead()).isTrue();
        assertThat(a.getReadAt()).isEqualTo(first);
        assertThat(b.getReadAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("DTO — 보낸 사람이 탈퇴(sender null)해도 알림은 남고 sender* 만 null")
    void dto_senderWithdrawn() {
        NotificationDto dto = NotificationDto.from(notification(10L, null, friend));
        assertThat(dto.getSenderId()).isNull();
        assertThat(dto.getSenderUsername()).isNull();
        assertThat(dto.getType()).isEqualTo(NotificationType.NUDGE);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private static Member member(Long id, String username) {
        return Member.builder().id(id).email(username + "@test.com").username(username)
                .password("x").role(UserRole.USER).build();
    }

    private static Notification notification(Long id, Member sender, Member recipient) {
        return Notification.builder().id(id).sender(sender).recipient(recipient)
                .type(NotificationType.NUDGE).targetDate(TODAY).build();
    }
}
