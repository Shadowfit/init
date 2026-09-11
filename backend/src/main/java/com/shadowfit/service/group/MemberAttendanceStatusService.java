package com.shadowfit.service.group;

import com.shadowfit.dto.group.MemberAttendanceStatusDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.GroupMemberRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.service.exercise.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모임 구성원 현황·홈 «친구의 운동 현황» — 남의 출석 상태를 읽는 유일한 자리.
 *
 * <p><b>권한</b> (social-cheer-and-group-feed.md §3-G, 2026-09-11 confirm): 같은 ACTIVE 그룹에
 * 있으면 본다. 구성원 현황은 요청자가 그 그룹의 ACTIVE 멤버인지로, 친구 현황은 «내가 속한 그룹들의
 * 멤버» 라는 대상 집합 자체로 판정이 끝난다. 노출 항목은 오늘 여부·연속일수 둘뿐이다.
 *
 * <p><b>친구 = 내가 속한 모임 멤버의 합집합(나 제외)</b> (§3-A b). friendships 테이블은 없다.
 *
 * <p><b>정렬</b> (2026-09-11 confirm, 레퍼런스 화면 순서): 오늘 완료 → 오늘은 안 했지만 streak 살아있음
 * → 기록 없음. 즉 attendedToday desc, streak desc, 동률은 username — 두 화면이 같은 순서를 보장한다.
 *
 * <p><b>비용</b>: «오늘 했나»는 멤버 N명을 IN 한 방, streak 는 멤버당 커서 쿼리(각 수 행,
 * {@link AttendanceService}). 12명이면 쿼리 ~13개.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberAttendanceStatusService {

    private static final Comparator<MemberAttendanceStatusDto> REFERENCE_ORDER =
            Comparator.comparing(MemberAttendanceStatusDto::isAttendedToday).reversed()
                    .thenComparing(Comparator.comparingInt(MemberAttendanceStatusDto::getStreak).reversed())
                    .thenComparing(MemberAttendanceStatusDto::getUsername);

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final AttendanceService attendanceService;

    /** 모임 구성원 현황 — 요청자 포함 ACTIVE 멤버 전원. 요청자가 ACTIVE 멤버가 아니면 403. */
    public List<MemberAttendanceStatusDto> groupMemberStatuses(Long groupId, Long requesterId, LocalDate today) {
        if (!groupRepository.existsById(groupId)) {
            throw new BusinessException(ErrorCode.GROUP_NOT_FOUND);
        }
        if (!groupMemberRepository.existsByGroupIdAndMemberIdAndStatus(groupId, requesterId, GroupMemberStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_MEMBER);
        }
        List<Member> members = groupMemberRepository
                .findAllWithMemberByGroupIdAndStatus(groupId, GroupMemberStatus.ACTIVE).stream()
                .map(GroupMember::getMember)
                .toList();
        return statusesOf(members, today);
    }

    /** 홈 «친구의 운동 현황» — 내가 ACTIVE 인 모든 그룹의 ACTIVE 멤버 합집합, 나는 제외. 모임이 없으면 빈 목록. */
    public List<MemberAttendanceStatusDto> friendStatuses(Long memberId, LocalDate today) {
        List<Long> myGroupIds = groupMemberRepository
                .findAllByMemberIdAndStatus(memberId, GroupMemberStatus.ACTIVE).stream()
                .map(gm -> gm.getGroup().getId())
                .toList();
        if (myGroupIds.isEmpty()) {
            return List.of();
        }
        // 같은 사람이 여러 모임에 겹쳐 있으면 한 번만 — id 기준 dedup (LinkedHashMap 으로 첫 등장 순서 유지,
        // 최종 순서는 어차피 정렬이 정한다).
        Map<Long, Member> distinct = new LinkedHashMap<>();
        for (GroupMember gm : groupMemberRepository.findAllWithMemberByGroupIdInAndStatus(myGroupIds, GroupMemberStatus.ACTIVE)) {
            Member m = gm.getMember();
            if (!m.getId().equals(memberId)) {
                distinct.putIfAbsent(m.getId(), m);
            }
        }
        return statusesOf(List.copyOf(distinct.values()), today);
    }

    private List<MemberAttendanceStatusDto> statusesOf(List<Member> members, LocalDate today) {
        Set<Long> attendedToday = attendanceService.attendedOn(members.stream().map(Member::getId).toList(), today);
        return members.stream()
                .map(m -> MemberAttendanceStatusDto.builder()
                        .memberId(m.getId())
                        .username(m.getUsername())
                        .profileImageUrl(m.getProfileImageUrl())
                        .attendedToday(attendedToday.contains(m.getId()))
                        .streak(attendanceService.currentStreak(m.getId(), today))
                        .build())
                .sorted(REFERENCE_ORDER)
                .toList();
    }
}
