package com.shadowfit.service.exercise;

import com.shadowfit.dto.admin.AdminExerciseDetailDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.repository.exercise.ExercisesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 mp4 업로드 → 기준 좌표 추출 — 세 자원(파일·DB·gRPC)의 <b>경계를 한 메서드에서 보이게</b> 둔 오케스트레이터.
 *
 * <p>이 클래스에는 {@code @Transactional} 이 없다. 순서가 곧 설계다:
 * <ol>
 *   <li>서킷 확인 — AI 가 죽어 있으면 아무것도 건드리지 않고 503 (confirm ㄴ)</li>
 *   <li>파일 저장 — 롤백이 안 되는 자원이라 <b>먼저</b> 한다. 뒤에서 실패하면 지우는 것(보상)이 가능하지만,
 *       DB 를 먼저 커밋한 뒤 파일이 실패하면 «경로는 있는데 파일이 없는» 행이 남는다</li>
 *   <li>트랜잭션 — {@link AdminExerciseService#attachReferenceVideo} 가 경로를 갱신하고 커밋 뒤 할 일을 건다</li>
 *   <li>커밋 뒤 — gRPC 추출 요청 발사, 이전 파일 삭제. 둘 다 트랜잭션 밖이라 커넥션을 쥐지 않고,
 *       롤백됐으면 불리지 않는다</li>
 *   <li>보상 — 3 이 던지면 2 에서 쓴 파일을 지운다</li>
 * </ol>
 *
 * <p>정답지({@code exercise_references}) 저장은 여기 없다. AI 가 추출을 마치고 Spring 을 역호출하면
 * {@code PoseDataService.saveReferencePoses} 가 자기 트랜잭션에서 교체한다(#220) — 이 흐름과 별개의 경계다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceVideoService {

    private final ExercisesRepository exercisesRepository;
    private final AdminExerciseService adminExerciseService;
    private final ExerciseAnalysisService exerciseAnalysisService;
    private final ReferenceVideoStorage storage;

    public AdminExerciseDetailDto upload(Long exerciseId, MultipartFile file) {
        // 존재 확인을 파일 저장보다 앞에 둔다 — 없는 id 로 디스크에 쓰고 지우는 왕복을 안 한다.
        // 값을 쓰지 않으므로 캐시된 조회로 충분하다(갱신은 3 에서 findById 로 다시 읽는다).
        if (exercisesRepository.findByIdCached(exerciseId).isEmpty()) {
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND);
        }

        // 1. 서킷 — 라우팅 키는 extractReferencePoses 와 같은 exerciseId 여야 같은 채널을 본다.
        if (!exerciseAnalysisService.isAiReachable(exerciseId)) {
            log.warn("AI 서킷 OPEN — 기준 영상 업로드 거부 (exerciseId={})", exerciseId);
            throw new BusinessException(ErrorCode.REFERENCE_EXTRACTION_UNAVAILABLE);
        }

        // 2. 파일 (트랜잭션 밖)
        String relative = storage.store(exerciseId, file);

        // 3. 트랜잭션 + 4. 커밋 뒤
        try {
            return adminExerciseService.attachReferenceVideo(exerciseId, relative, previous -> {
                exerciseAnalysisService.extractReferencePoses(exerciseId, storage.aiPath(relative));
                if (previous != null && !previous.equals(relative)) {
                    storage.deleteQuietly(previous);
                }
            });
        } catch (RuntimeException e) {
            // 5. 보상 — DB 가 실패했으니 방금 쓴 파일은 가리키는 행이 없다.
            storage.deleteQuietly(relative);
            throw e;
        }
    }
}
