package com.shadowfit.service.Exercise;

import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.repository.exercise.ExercisesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminExerciseService {
    private final ExercisesRepository exercisesRepository;

    // findById(캐시 미적용) 유지 — 캐시된 findByIdCached는 detached 엔티티를 반환해
    // 아래 setter가 dirty-checking에 안 잡히고 조용히 무시됨. evict만 캐시에 반영.
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public ExerciseThresholdResponseDto updateThresholds(Long exerciseId, ThresholdUpdateDto dto) {
        // beginner < advanced만 검증. diet/rehab은 숙련도 축이 아니라 목적(체중감량/안전)이 달라
        // beginner·advanced와 순서 관계를 강제할 이유가 없음 — 개별 범위(0~100)만 DTO에서 검증.
        if (dto.beginner().compareTo(dto.advanced()) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Exercise exercise = exercisesRepository.findById(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        log.info("운동 {} 임계값 변경: beginner {} -> {}, advanced {} -> {}, diet {} -> {}, rehab {} -> {}",
                exerciseId,
                exercise.getSyncThresholdBeginner(), dto.beginner(),
                exercise.getSyncThresholdAdvanced(), dto.advanced(),
                exercise.getSyncThresholdDiet(), dto.diet(),
                exercise.getSyncThresholdRehab(), dto.rehab());

        exercise.setSyncThresholdBeginner(dto.beginner());
        exercise.setSyncThresholdAdvanced(dto.advanced());
        exercise.setSyncThresholdDiet(dto.diet());
        exercise.setSyncThresholdRehab(dto.rehab());

        return ExerciseThresholdResponseDto.fromEntity(exercise);
    }
}