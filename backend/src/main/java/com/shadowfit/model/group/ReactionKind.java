package com.shadowfit.model.group;

/**
 * 피드 리액션 종류 — social-cheer-and-group-feed.md §3-D(💗🔥) · §4-5 ⑥. 이모지 렌더링은 프론트 몫이고
 * 서버는 이름만 안다. DB 컬럼은 VARCHAR(20)(DB ENUM 아님 — {@code NotificationType} 과 같은 결).
 */
public enum ReactionKind {
    HEART,
    FIRE
}
