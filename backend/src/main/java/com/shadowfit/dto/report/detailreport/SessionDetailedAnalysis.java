package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * {@code reports.detailed_analysis}(JSON 컬럼)에 저장되는 precompute 결과 묶음.
 *
 * <p>precompute-on-write(report-read-path.md §9)는 세션 완료 시점에 계산해 두고 조회 때는
 * {@code pose_data} 를 스캔하지 않는 것이 목적이다. 회차별 추이도 같은 스캔에서 나오므로
 * worst 와 <b>함께 저장</b>한다 — 따로 두면 추이를 위해 스캔이 되살아난다.
 *
 * <p><b>구버전 형식 주의</b>: 이전에는 이 컬럼에 {@link WorstSectionDto} 를 그대로 직렬화했다
 * ({@code {"exerciseName":...,"timeStamp":...,"reason":...}}). 그 형식으로 저장된 행을 이 타입으로
 * 읽으면 필드가 하나도 안 맞아 파싱 실패하거나 전부 null 이 된다. 읽기 경로가 두 경우를 모두
 * 구버전으로 판정해 {@code pose_data} 재계산으로 흘린다(ReportService.resolveDetailedAnalysis).
 *
 * <p>실측 기준 그런 행은 <b>0건</b>이다(2026-08-01, decisions/worst-section-rep-resolution.md §6-1)
 * — 마이그레이션이 필요 없는 이유이자, 하위호환 경로가 사실상 죽어 있다는 뜻이기도 하다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetailedAnalysis {

    /** 싱크로율이 가장 낮았던 회차. 측정된 rep 이 없으면 null. */
    private WorstSectionDto worstSection;

    /** 회차별 싱크로율(rep 오름차순). 측정된 rep 이 없으면 빈 리스트. */
    private List<RepSyncRateDto> repTrend;

    /**
     * 스쿼트 깊이 정확도 리포트 (2026-09-21). 스쿼트가 아니거나 깊이 데이터가 없으면 null —
     * 구버전으로 저장된 행을 이 타입으로 읽어도 필드가 없으니 자동으로 null이 된다(하위호환).
     */
    private SquatDepthAnalysisDto squatDepthAnalysis;

    /**
     * 런지 깊이 정확도 리포트 (2026-09-27). 런지가 아니거나 좌우 구분 깊이 데이터가 없으면
     * null — 위와 같은 하위호환 규약(V30 이전에 완료된 런지 세션도 이 필드가 null이 된다).
     */
    private LungeDepthAnalysisDto lungeDepthAnalysis;

    /**
     * 스쿼트 체간 기울기 정확도 리포트 (2026-09-28). 스쿼트가 아니거나 체간 기울기 데이터가
     * 없으면 null — 위와 같은 하위호환 규약(V31 이전에 완료된 세션도 이 필드가 null이 된다).
     */
    private TrunkLeanAnalysisDto squatTrunkLeanAnalysis;

    /**
     * 런지 체간 기울기 정확도 리포트 (2026-09-28). 런지가 아니거나 체간 기울기 데이터가
     * 없으면 null — 위와 같은 하위호환 규약.
     */
    private TrunkLeanAnalysisDto lungeTrunkLeanAnalysis;

    /**
     * 런지 보폭 정확도 리포트 (2026-09-28). 런지가 아니거나 보폭 데이터가 없으면 null —
     * 위와 같은 하위호환 규약(V32 이전에 완료된 세션도 이 필드가 null이 된다). 스쿼트는
     * 보폭 개념이 없어 대응 필드 자체가 없다.
     */
    private LungeStepLengthAnalysisDto lungeStepLengthAnalysis;
}
