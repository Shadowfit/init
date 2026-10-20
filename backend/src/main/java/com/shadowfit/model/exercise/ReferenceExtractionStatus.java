package com.shadowfit.model.exercise;

/**
 * 기준 좌표 등록의 <b>가장 최근 시도</b> 결과 — {@code exercises.reference_extraction_status}(V34).
 *
 * <p>컬럼이 NULL 이면 한 번도 등록을 시도하지 않은 종목이다(V4 시드 스쿼트처럼 SQL 로 넣은 기준). 관리자 화면에는
 * {@code NONE} 으로 나간다 — 그 값은 저장하지 않으므로 여기 없다.
 */
public enum ReferenceExtractionStatus {
    /** 영상 업로드 뒤 AI 의 비동기 추출을 기다리는 중 */
    EXTRACTING,
    /** 기준 좌표가 교체됐다(영상 추출 완료 또는 3D 시범 직접 등록) */
    READY,
    /** 추출이 실패했다 — 사유는 {@code reference_extraction_error}. 기존 기준 좌표는 그대로다 */
    FAILED
}
