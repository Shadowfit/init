package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.SessionSet;
import com.shadowfit.model.exercise.SessionSetId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SessionSetRepository extends JpaRepository<SessionSet, SessionSetId> {

    List<SessionSet> findBySessionIdOrderBySetNo(Long sessionId);

    // 목록 화면(주간·일별)이 세션마다 세트 표기를 만들 때 한 번에 읽는다 — 세션 수만큼 쿼리가 나가지 않게.
    List<SessionSet> findBySessionIdInOrderBySessionIdAscSetNoAsc(Collection<Long> sessionIds);

    // 세션 삭제(DELETE /sessions/{id}). MySQL 은 FK CASCADE 가 지우지만 H2(테스트, 엔티티 생성 스키마)엔 그 옵션이
    // 없어 명시적으로 지운다 — pose_data 를 deleteBySessionIdIn 으로 지우는 것과 같은 자리.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SessionSet s WHERE s.session.id = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);
}
