package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.Exercise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExercisesRepository extends JpaRepository<Exercise,Long> {
}
