package com.shadowfit.service.exercise;

import com.shadowfit.dto.exercises.ExerciseCatalogItemDto;
import com.shadowfit.repository.exercise.ExercisesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 회원용 종목 목록 ({@code GET /exercises}, lunge-and-set-backend.md §3-C).
 *
 * <p><b>캐시하지 않는다.</b> {@code exercises} 캐시(단건, 1h)에 목록을 얹으려면 관리자 쓰기 경로 5곳
 * (등록·수정·삭제·분석 활성화·기준 영상)에 {@code allEntries} evict 를 빠짐없이 걸어야 하고, 하나라도 빠지면
 * «켰는데 화면엔 준비 중」 이 최대 1시간 간다 — {@code analysis_supported} 가 이미 한 번 겪은 함정이다
 * ({@code AdminExerciseService.updateAnalysisSupport} 주석). 행 수가 한 자리이고 호출이 화면 진입당 1회라
 * 캐시가 사는 비용이 없다. 종목이 수십 개가 되고 호출이 잦아지면 그때 실측하고 넣는다.
 */
@Service
@RequiredArgsConstructor
public class ExerciseCatalogService {

    private final ExercisesRepository exercisesRepository;

    @Transactional(readOnly = true)
    public List<ExerciseCatalogItemDto> getCatalog() {
        return exercisesRepository.findAllForCatalog().stream()
                .map(ExerciseCatalogItemDto::fromEntity)
                .toList();
    }
}
