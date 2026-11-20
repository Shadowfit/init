package com.shadowfit.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.admin.AdminCommunityStatsDto;
import com.shadowfit.dto.admin.AdminDailyStatDto;
import com.shadowfit.dto.admin.AdminFeedPostDto;
import com.shadowfit.dto.admin.AdminGroupDetailDto;
import com.shadowfit.dto.admin.AdminGroupListItemDto;
import com.shadowfit.dto.admin.AdminMemberDetailDto;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.group.GroupEventTypes;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.notification.NotificationType;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.group.FeedPhotoStorage;
import com.shadowfit.service.member.AdminMemberService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 화면이 새로 쓰는 읽기·운영 기능 — 회원 상세, 모임, 피드 공유 글 검수, 대시보드 추이·커뮤니티 지표.
 *
 * <p>기존 회원·세션·종목 목록은 필터가 많아 QueryDSL 로 짰다(querydsl-adoption.md). 여기 목록들은 필터가 검색어
 * 하나 이하라 JPQL 로 충분하고, 페이지 크기 상한·기본값은 기존 관리자 목록과 같은 값을 쓴다.
 * 집계는 매번 센다 — 관리자 대시보드는 조회가 드물고 표가 작다(admin-page-scope.md §2-D «사전집계는 근거가 생기면»).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminInsightService {

    private static final int RECENT_SESSIONS = 10;
    private static final int MAX_DAILY_DAYS = 90;

    private final EntityManager em;
    private final MemberRepository memberRepository;
    private final GroupRepository groupRepository;
    private final FeedPhotoStorage feedPhotoStorage;
    private final ObjectMapper objectMapper;

    /* ───────────── 회원 상세 ───────────── */

    @Transactional(readOnly = true)
    public AdminMemberDetailDto memberDetail(Long memberId) {
        Member m = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Object[] agg = em.createQuery(
                        "select count(s), "
                                + "sum(case when s.status = :done then 1 else 0 end), "
                                + "avg(case when s.status = :done then s.avgSyncRate end), "
                                + "max(s.startTime) "
                                + "from Session s where s.member.id = :id", Object[].class)
                .setParameter("done", Status.COMPLETED)
                .setParameter("id", memberId)
                .getSingleResult();

        List<AdminMemberDetailDto.RecentSession> recent = em.createQuery(
                        "select s from Session s join fetch s.exercise where s.member.id = :id order by s.startTime desc",
                        Session.class)
                .setParameter("id", memberId)
                .setMaxResults(RECENT_SESSIONS)
                .getResultList().stream()
                .map(s -> new AdminMemberDetailDto.RecentSession(s.getId(), s.getExercise().getName(), s.getStatus(),
                        s.getStartTime(), s.getTotalReps(), s.getAvgSyncRate()))
                .toList();

        List<AdminMemberDetailDto.GroupMembership> groups = em.createQuery(
                        "select gm from GroupMember gm join fetch gm.group "
                                + "where gm.member.id = :id and gm.status = :active order by gm.joinedAt asc",
                        GroupMember.class)
                .setParameter("id", memberId)
                .setParameter("active", GroupMemberStatus.ACTIVE)
                .getResultList().stream()
                .map(gm -> new AdminMemberDetailDto.GroupMembership(gm.getGroup().getId(), gm.getGroup().getName(), gm.getRole()))
                .toList();

        return new AdminMemberDetailDto(m.getId(), m.getUsername(), m.getEmail(), m.getRole(), m.getSex(),
                m.getSelectedPersona(), m.getWorkoutLevel(), m.getHeight(), m.getWeight(), m.isOnboardingCompleted(),
                m.getCreatedAt(), toLong(agg[0]), toLong(agg[1]), toDouble(agg[2]), (LocalDateTime) agg[3],
                groups, recent);
    }

    /* ───────────── 모임 ───────────── */

    @Transactional(readOnly = true)
    public PageResponse<AdminGroupListItemDto> groups(String keyword, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = safeSize(size);
        String kw = keyword == null || keyword.isBlank() ? null : "%" + keyword.strip().toLowerCase() + "%";

        String where = kw == null ? "" : " where lower(g.name) like :kw";
        var countQ = em.createQuery("select count(g) from Group g" + where, Long.class);
        var listQ = em.createQuery("select g from Group g" + where + " order by g.createdAt desc, g.id desc", Group.class);
        if (kw != null) {
            countQ.setParameter("kw", kw);
            listQ.setParameter("kw", kw);
        }
        long total = countQ.getSingleResult();
        List<Group> found = listQ.setFirstResult(safePage * safeSize).setMaxResults(safeSize).getResultList();
        return PageResponse.of(summaries(found), safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public AdminGroupDetailDto groupDetail(Long groupId) {
        Group g = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
        List<AdminGroupDetailDto.Member> members = em.createQuery(
                        "select gm from GroupMember gm join fetch gm.member where gm.group.id = :id order by gm.joinedAt asc",
                        GroupMember.class)
                .setParameter("id", groupId)
                .getResultList().stream()
                .map(gm -> new AdminGroupDetailDto.Member(gm.getMember().getId(), gm.getMember().getUsername(),
                        gm.getRole(), gm.getStatus(), gm.getJoinedAt()))
                .toList();
        return new AdminGroupDetailDto(summaries(List.of(g)).get(0), members);
    }

    /** 모임 삭제 — 멤버십·피드·응원·리액션은 FK CASCADE 가 지운다. 공유 사진 파일은 DB 밖이라 여기서 지운다. */
    @Transactional
    public void deleteGroup(Long groupId) {
        Group g = groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
        List<String> photos = em.createQuery(
                        "select e.payload from GroupEvent e where e.group.id = :id and e.eventType = :t", String.class)
                .setParameter("id", groupId)
                .setParameter("t", GroupEventTypes.SESSION_SHARED)
                .getResultList().stream()
                .map(this::photoNameOf)
                .filter(n -> n != null)
                .toList();
        groupRepository.delete(g);
        em.flush();
        photos.forEach(feedPhotoStorage::deleteQuietly);
        log.info("관리자 모임 삭제 — groupId={}, name={}, photos={}", groupId, g.getName(), photos.size());
    }

    private List<AdminGroupListItemDto> summaries(List<Group> groups) {
        if (groups.isEmpty()) {
            return List.of();
        }
        List<Long> ids = groups.stream().map(Group::getId).toList();
        Map<Long, Long> members = countBy(
                "select gm.group.id, count(gm) from GroupMember gm where gm.group.id in :ids and gm.status = :active group by gm.group.id",
                ids, Map.of("active", GroupMemberStatus.ACTIVE));
        Map<Long, Long> posts = countBy(
                "select e.group.id, count(e) from GroupEvent e where e.group.id in :ids and e.eventType = :t group by e.group.id",
                ids, Map.of("t", GroupEventTypes.SESSION_SHARED));
        Map<Long, LocalDateTime> lastActivity = new HashMap<>();
        for (Object[] row : em.createQuery(
                        "select e.group.id, max(e.createdAt) from GroupEvent e where e.group.id in :ids group by e.group.id", Object[].class)
                .setParameter("ids", ids).getResultList()) {
            lastActivity.put((Long) row[0], (LocalDateTime) row[1]);
        }
        Map<Long, String> owners = new HashMap<>();
        for (Object[] row : em.createQuery(
                        "select gm.group.id, gm.member.username from GroupMember gm "
                                + "where gm.group.id in :ids and gm.role = :owner and gm.status = :active", Object[].class)
                .setParameter("ids", ids)
                .setParameter("owner", GroupRole.OWNER)
                .setParameter("active", GroupMemberStatus.ACTIVE)
                .getResultList()) {
            owners.put((Long) row[0], (String) row[1]);
        }
        return groups.stream().map(g -> new AdminGroupListItemDto(g.getId(), g.getName(), g.getDescription(),
                        g.getInviteCode(), owners.get(g.getId()), members.getOrDefault(g.getId(), 0L),
                        posts.getOrDefault(g.getId(), 0L), g.getCreatedAt(), lastActivity.get(g.getId())))
                .toList();
    }

    /* ───────────── 피드 공유 글 검수 ───────────── */

    @Transactional(readOnly = true)
    public PageResponse<AdminFeedPostDto> feedPosts(Long groupId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = safeSize(size);
        String where = " where e.eventType = :t" + (groupId == null ? "" : " and e.group.id = :gid");
        var countQ = em.createQuery("select count(e) from GroupEvent e" + where, Long.class)
                .setParameter("t", GroupEventTypes.SESSION_SHARED);
        var listQ = em.createQuery("select e from GroupEvent e join fetch e.group left join fetch e.sender" + where
                        + " order by e.createdAt desc, e.id desc", GroupEvent.class)
                .setParameter("t", GroupEventTypes.SESSION_SHARED);
        if (groupId != null) {
            countQ.setParameter("gid", groupId);
            listQ.setParameter("gid", groupId);
        }
        long total = countQ.getSingleResult();
        List<GroupEvent> events = listQ.setFirstResult(safePage * safeSize).setMaxResults(safeSize).getResultList();

        Map<Long, Long> cheers = events.isEmpty() ? Map.of() : countBy(
                "select c.event.id, count(c) from EventCheer c where c.event.id in :ids group by c.event.id",
                events.stream().map(GroupEvent::getId).toList(), Map.of());

        List<AdminFeedPostDto> items = events.stream().map(e -> {
            JsonNode p = parse(e.getPayload());
            return new AdminFeedPostDto(e.getId(), e.getGroup().getId(), e.getGroup().getName(), e.getSeq(),
                    e.getSender() == null ? null : e.getSender().getId(),
                    e.getSender() == null ? text(p, "username") : e.getSender().getUsername(),
                    text(p, "exerciseName"), p.hasNonNull("totalReps") ? p.get("totalReps").asInt() : null,
                    text(p, "caption"), text(p, "photoUrl"), cheers.getOrDefault(e.getId(), 0L), e.getCreatedAt());
        }).toList();
        return PageResponse.of(items, safePage, safeSize, total);
    }

    /** 공유 글 삭제 — 응원·리액션은 FK CASCADE. 사진 파일도 지운다. 공유 글이 아니면 404(자동 글·가입 글은 운영 대상이 아니다). */
    @Transactional
    public void deleteFeedPost(Long postId) {
        GroupEvent e = em.find(GroupEvent.class, postId);
        if (e == null || !GroupEventTypes.SESSION_SHARED.equals(e.getEventType())) {
            throw new BusinessException(ErrorCode.GROUP_EVENT_NOT_FOUND);
        }
        String photo = photoNameOf(e.getPayload());
        em.remove(e);
        em.flush();
        feedPhotoStorage.deleteQuietly(photo);
        log.info("관리자 공유 글 삭제 — postId={}, groupId={}", postId, e.getGroup().getId());
    }

    /* ───────────── 대시보드 ───────────── */

    /** 최근 N일(오늘 포함) 하루치 추이 — 세션이 없는 날도 0 으로 채워 그래프 축이 끊기지 않게 한다. */
    @Transactional(readOnly = true)
    public List<AdminDailyStatDto> daily(int days, LocalDate today) {
        int n = Math.max(1, Math.min(days, MAX_DAILY_DAYS));
        LocalDate from = today.minusDays(n - 1L);
        LocalDateTime fromAt = from.atStartOfDay();

        Map<LocalDate, Object[]> sessions = new HashMap<>();
        for (Object row : em.createNativeQuery(
                        "select cast(start_time as date) d, count(*) c, "
                                + "sum(case when status = 'COMPLETED' then 1 else 0 end) done, "
                                + "avg(case when status = 'COMPLETED' then avg_sync_rate end) av "
                                + "from exercise_sessions where start_time >= ?1 group by cast(start_time as date)")
                .setParameter(1, fromAt).getResultList()) {
            Object[] r = (Object[]) row;
            sessions.put(toDate(r[0]), r);
        }
        Map<LocalDate, Long> joined = new HashMap<>();
        for (Object row : em.createNativeQuery(
                        "select cast(created_at as date) d, count(*) c from users where created_at >= ?1 group by cast(created_at as date)")
                .setParameter(1, fromAt).getResultList()) {
            Object[] r = (Object[]) row;
            joined.put(toDate(r[0]), toLong(r[1]));
        }

        List<AdminDailyStatDto> out = new ArrayList<>(n);
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            Object[] s = sessions.get(d);
            out.add(new AdminDailyStatDto(d, s == null ? 0 : toLong(s[1]), s == null ? 0 : toLong(s[2]),
                    s == null ? null : toDouble(s[3]), joined.getOrDefault(d, 0L)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AdminCommunityStatsDto community(LocalDate today) {
        long groups = em.createQuery("select count(g) from Group g", Long.class).getSingleResult();
        long memberships = em.createQuery("select count(gm) from GroupMember gm where gm.status = :a", Long.class)
                .setParameter("a", GroupMemberStatus.ACTIVE).getSingleResult();
        long posts = em.createQuery("select count(e) from GroupEvent e where e.eventType = :t", Long.class)
                .setParameter("t", GroupEventTypes.SESSION_SHARED).getSingleResult();
        long cheers = em.createQuery("select count(c) from EventCheer c", Long.class).getSingleResult();
        long nudges = em.createQuery("select count(n) from Notification n where n.type = :t and n.targetDate = :d", Long.class)
                .setParameter("t", NotificationType.NUDGE).setParameter("d", today).getSingleResult();
        return new AdminCommunityStatsDto(groups, memberships, posts, cheers, nudges);
    }

    /* ───────────── 공통 ───────────── */

    private Map<Long, Long> countBy(String jpql, List<Long> ids, Map<String, Object> params) {
        var q = em.createQuery(jpql, Object[].class).setParameter("ids", ids);
        params.forEach(q::setParameter);
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Object[] row : q.getResultList()) {
            out.put((Long) row[0], toLong(row[1]));
        }
        return out;
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload == null ? "{}" : payload);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asText() : null;
    }

    private String photoNameOf(String payload) {
        String url = text(parse(payload), "photoUrl");
        return url == null ? null : url.substring(url.lastIndexOf('/') + 1);
    }

    private static int safeSize(int size) {
        return size <= 0 ? AdminMemberService.DEFAULT_PAGE_SIZE : Math.min(size, AdminMemberService.MAX_PAGE_SIZE);
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        double v = o instanceof BigDecimal b ? b.doubleValue() : ((Number) o).doubleValue();
        return Math.round(v * 10.0) / 10.0;
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof Date d) return d.toLocalDate();
        if (o instanceof java.util.Date d) return new Date(d.getTime()).toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }
}
