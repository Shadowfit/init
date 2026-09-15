package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseFeedbackTemplate;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.model.member.SelectedPersona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code uk_exercise_feedback_persona_key} — persona NULL fallback 행도 종목·결함당 한 줄인가 (#715).
 *
 * <p>이슈가 «재현은 안 했다» 고 적은 그 재현이다. 옛 UNIQUE(persona 직접)로는 아래 첫 테스트가
 * 통과해 버린다 — NULL ≠ NULL 이라서. H2 도 같은 규칙이라 테스트 DB 에서 그대로 드러난다.
 */
@SpringBootTest
@Transactional
@DisplayName("ExerciseFeedbackTemplate 유니크 — persona NULL fallback")
class ExerciseFeedbackTemplateRepositoryTest {

    @Autowired private ExerciseFeedbackTemplateRepository templateRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Exercise exercise;

    @BeforeEach
    void setUp() {
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("런지").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
    }

    private ExerciseFeedbackTemplate template(FeedbackType type, SelectedPersona persona, String message) {
        return ExerciseFeedbackTemplate.builder()
                .exercise(exercise).feedbackType(type).persona(persona).message(message).build();
    }

    @Test
    @DisplayName("같은 (종목, 결함) 의 fallback(persona NULL) 행은 두 번 못 들어간다")
    void duplicateFallback_isRejected() {
        templateRepository.saveAndFlush(template(FeedbackType.KNEE_OUT, null, "무릎을 모으세요"));

        assertThatThrownBy(() -> templateRepository.saveAndFlush(
                template(FeedbackType.KNEE_OUT, null, "무릎이 벌어졌어요")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("fallback 과 페르소나 행, 다른 결함의 fallback 은 공존한다 — 제약이 과하게 걸리지 않는다")
    void fallbackAndPersonaRows_coexist() {
        templateRepository.saveAndFlush(template(FeedbackType.KNEE_OUT, null, "공통"));
        templateRepository.saveAndFlush(template(FeedbackType.KNEE_OUT, SelectedPersona.BEGINNER, "초보용"));
        templateRepository.saveAndFlush(template(FeedbackType.KNEE_OUT, SelectedPersona.ADVANCED, "고급용"));
        templateRepository.saveAndFlush(template(FeedbackType.BACK_BENT, null, "다른 결함 공통"));

        assertThat(templateRepository.findByExerciseIdAndPersonaIsNullOrderByPriorityAsc(exercise.getId()))
                .extracting(ExerciseFeedbackTemplate::getFeedbackType)
                .containsExactlyInAnyOrder(FeedbackType.KNEE_OUT, FeedbackType.BACK_BENT);
        assertThat(templateRepository.findByExerciseIdAndPersonaOrderByPriorityAsc(exercise.getId(), SelectedPersona.BEGINNER))
                .hasSize(1);
    }

    @Test
    @DisplayName("같은 (종목, 결함, 페르소나) 는 전처럼 막힌다")
    void duplicatePersonaRow_isRejected() {
        templateRepository.saveAndFlush(template(FeedbackType.KNEE_OUT, SelectedPersona.DIET, "다이어트용"));

        assertThatThrownBy(() -> templateRepository.saveAndFlush(
                template(FeedbackType.KNEE_OUT, SelectedPersona.DIET, "또 다이어트용")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
