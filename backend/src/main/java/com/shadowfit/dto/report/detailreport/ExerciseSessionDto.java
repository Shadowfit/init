package com.shadowfit.dto.report.detailreport;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExerciseSessionDto {
    private boolean scoreAvailable;
    /**
     * 이 세션에서 <b>측정된 것이 하나라도 있는가</b> — 인정 반복이 있거나 점수가 매겨졌다(미인정 시도 포함 평균이 있다).
     * {@code scoreAvailable=false} 만으로는 «횟수만 재는 종목»(런지: 횟수는 있고 점수만 없음)과 «아무것도 측정되지 않은 세션»
     * (카메라가 가려졌거나 사람이 안 잡혀 0회·점수 없음)을 가를 수 없다 — 앱이 둘을 같은 «횟수 기록» 으로 보여 줬다.
     * false 면 앱은 «측정 안 됨» 으로 표시한다. 기본값을 true 로 둔 것은 옛 서버·옛 응답과의 호환이 아니라(클라가 {@code === false} 로 본다)
     * 이 필드를 모르는 코드 경로가 «측정됨» 으로 읽히게 하려는 것이다.
     */
    private boolean measured = true;
    private Long sessionId;              // 클릭 시 이동을 위한 ID
    private String exerciseName;         // "숄더프레스"
    private String setSummary;           // "4세트 x 10회"
    private double syncRate;             // 93
}
