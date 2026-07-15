package com.shadowfit.dto.report;

// worst 구간 계산에 필요한 3컬럼만. joint_coordinates(2.3KB) 제외 → off-page I/O 회피.
public record PoseFrameProjection(Double timestampSec, Double syncRate, String feedbackMessage) {}
