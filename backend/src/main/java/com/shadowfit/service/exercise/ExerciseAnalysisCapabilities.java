package com.shadowfit.service.exercise;

/** 미측정 점수를 실제 0점으로 취급하지 않도록 종목의 분석 범위를 확인한다. */
public final class ExerciseAnalysisCapabilities {
    private ExerciseAnalysisCapabilities() {}

    public static boolean isCountOnly(com.shadowfit.model.exercise.Exercise exercise) {
        return exercise != null && Boolean.TRUE.equals(exercise.getRepetitionOnly());
    }

    public static boolean isCountOnly(com.shadowfit.model.exercise.Session session) {
        return isCountOnly(session.getExercise());
    }
}
