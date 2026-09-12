package com.shadowfit.service.notification;

import com.shadowfit.dto.notification.NotificationListResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.Notification;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.notification.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 통합테스트(H2, 단일 트랜잭션)가 못 만드는 두 경우 — 사전 검사를 둘 다 통과한 더블탭이 UNIQUE 에서
 * 걸리는 경합, 그리고 size 경계 클램프.
 */
@DisplayName("NotificationService 테스트")
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private MemberRepository memberRepository;

    private NotificationService service;
    private final LocalDate today = LocalDate.of(2026, 9, 12);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationService(notificationRepository, groupMemberRepository, memberRepository);
        when(memberRepository.findById(2L)).thenReturn(Optional.of(member(2L)));
        when(memberRepository.getReferenceById(1L)).thenReturn(member(1L));
        when(groupMemberRepository.countSharedGroups(1L, 2L, GroupMemberStatus.ACTIVE)).thenReturn(1L);
    }

    @Test
    @DisplayName("더블탭 — 사전 exists 는 false 였는데 flush 에서 UNIQUE 위반이 나면 500 이 아니라 같은 409")
    void nudge_raceOnUnique_conflict() {
        when(notificationRepository.existsBySenderIdAndRecipientIdAndTypeAndTargetDate(1L, 2L, NotificationType.NUDGE, today))
                .thenReturn(false);
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notifications_daily"));

        assertThatThrownBy(() -> service.nudge(1L, 2L, today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NUDGE_ALREADY_SENT_TODAY);
    }

    @Test
    @DisplayName("사전 검사 순서 — self 는 DB 를 안 건드리고, 같은 그룹이 없으면 exists 검사까지 안 간다")
    void nudge_guardsBeforeQueries() {
        assertThatThrownBy(() -> service.nudge(1L, 1L, today))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NUDGE_SELF);
        verify(memberRepository, never()).findById(anyLong());

        when(groupMemberRepository.countSharedGroups(1L, 2L, GroupMemberStatus.ACTIVE)).thenReturn(0L);
        assertThatThrownBy(() -> service.nudge(1L, 2L, today))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
        verify(notificationRepository, never()).existsBySenderIdAndRecipientIdAndTypeAndTargetDate(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("size 클램프 — null→20, 0→1, 999→50. 조회는 항상 size+1 건")
    void list_pageSizeClamp() {
        when(notificationRepository.findPageByRecipient(eq(2L), isNull(), any(Pageable.class))).thenReturn(List.of());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        service.list(2L, null, null);
        service.list(2L, null, 0);
        service.list(2L, null, 999);

        verify(notificationRepository, org.mockito.Mockito.times(3)).findPageByRecipient(eq(2L), isNull(), pageable.capture());
        assertThat(pageable.getAllValues().stream().map(Pageable::getPageSize).toList())
                .containsExactly(21, 2, 51);
    }

    @Test
    @DisplayName("hasNext — size+1 건이 오면 마지막을 떼고 nextCursor 는 남은 마지막 id")
    void list_trimsExtraRow() {
        List<Notification> rows = IntStream.of(30, 29, 28)
                .mapToObj(i -> Notification.builder().id((long) i).recipient(member(2L)).type(NotificationType.NUDGE).targetDate(today).build())
                .toList();
        when(notificationRepository.findPageByRecipient(eq(2L), isNull(), any(Pageable.class))).thenReturn(rows);

        NotificationListResponseDto page = service.list(2L, null, 2);

        assertThat(page.getItems()).extracting("id").containsExactly(30L, 29L);
        assertThat(page.isHasNext()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(29L);
    }

    private Member member(Long id) {
        return Member.builder().id(id).email(id + "@test.com").username("m" + id).build();
    }
}
