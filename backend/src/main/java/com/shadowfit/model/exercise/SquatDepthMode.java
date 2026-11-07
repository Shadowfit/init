package com.shadowfit.model.exercise;

/**
 * 스쿼트 깊이 모드. 운동 시작 전 종목 선택과 같은 화면에서 사용자가 고른다(2026-09-21).
 * 스쿼트가 아닌 세션에서는 의미가 없다 — 항상 FULL로 채워지지만 무시된다.
 */
public enum SquatDepthMode {
    FULL,  // 무릎각 <=115도 — 기존 기본 판정, 변경 없음
    HALF   // 무릎각 115~140도 밴드
}
