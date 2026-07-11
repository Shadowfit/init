package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.ExerciseReference;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExerciseReferenceRepository extends JpaRepository<ExerciseReference, Long> {

    // 이 조회는 saveReferencePoses(쓰기 경로)에서 재사용되지 않고 gRPC 전송용 읽기 전용 경로에서만
    // 쓰이므로, exercises의 findByIdCached와 달리 findByExerciseId 자체에 바로 캐시를 붙여도 안전.
    @Cacheable(cacheNames = "exerciseReferences", key = "#exerciseId")
    List<ExerciseReference> findByExerciseId(Long exerciseId);
}