package com.shadowfit.service.exercise;

import com.shadowfit.dto.exercises.ExerciseCatalogItemDto;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.repository.exercise.ExercisesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 회원용 종목 목록 ({@code GET /exercises}) — lunge-and-set-backend.md §3-C.
 *
 * <p>여기서 고정하는 것은 하나다: <b>{@code analysisSupported=false} 인 종목이 목록에서 사라지지 않는다.</b>
 * «세션을 못 여는 종목은 빼자» 는 편의가 들어오면 화면에서 런지가 통째로 없어지고, 그 순간 «준비 중» 을
 * 그릴 수 있는 정보도 같이 없어진다. 조회 SQL 자체는 {@code ExercisesRepository.findAllForCatalog} 의 JPQL 이라
 * 여기서 검증하지 않는다(컨텍스트를 안 띄운다).
 */
class ExerciseCatalogServiceTest {

    private final ExercisesRepository exercisesRepository = mock(ExercisesRepository.class);
    private final ExerciseCatalogService service = new ExerciseCatalogService(exercisesRepository);

    @Test
    @DisplayName("분석 미지원 종목도 목록에 남고, code 가 없는 종목은 null 로 내려간다")
    void catalog_keepsUnsupportedAndNullCode() {
        Category lower = Category.builder().id(10L).name("LOWER").build();
        Exercise squat = Exercise.builder().id(1L).name("스쿼트").code("SQUAT").category(lower)
                .analysisSupported(true).build();
        Exercise lunge = Exercise.builder().id(2L).name("런지").code("LUNGE").category(lower).build();
        Exercise custom = Exercise.builder().id(4L).name("케틀벨 스윙").category(lower).build();
        when(exercisesRepository.findAllForCatalog()).thenReturn(List.of(squat, lunge, custom));

        List<ExerciseCatalogItemDto> items = service.getCatalog();

        assertThat(items).extracting(ExerciseCatalogItemDto::id).containsExactly(1L, 2L, 4L);
        assertThat(items).extracting(ExerciseCatalogItemDto::analysisSupported).containsExactly(true, false, false);
        assertThat(items).extracting(ExerciseCatalogItemDto::code).containsExactly("SQUAT", "LUNGE", null);
        assertThat(items.get(0).categoryName()).isEqualTo("LOWER");
        assertThat(items.get(0).expectedDurationMinutes()).isEqualTo(15);
    }
}
