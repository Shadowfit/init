package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.error.GlobalExceptionHandler;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.exercise.ExerciseAnalysisService;
import com.shadowfit.service.exercise.ReferenceVideoProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/exercises/{id}/reference-video} 의 HTTP 계약 + <b>실제 파일·실제 커밋</b>.
 *
 * <p>일부러 {@code @Transactional} 을 안 건다. 이 API 의 핵심이 «커밋 <b>뒤에</b> gRPC 가 나간다» 인데, 테스트
 * 트랜잭션은 롤백으로 끝나서 {@code afterCommit} 이 영영 안 불린다 — 걸면 그 동작을 검증할 수 없다. 대신
 * {@code @AfterEach} 가 행과 파일을 손으로 치운다.
 *
 * <p>gRPC 자체는 대역이다({@link MockitoBean}). 실제로 나가는 값(«ai-dir + 상대경로»)만 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("관리자 기준 영상 업로드 통합테스트")
class ReferenceVideoUploadIntegrationTest {

    /** ISO BMFF 파일 머리 — 크기(4) + "ftyp" + 브랜드. 검증기는 4~8바이트만 본다. */
    private static final byte[] MP4_HEAD = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ReferenceVideoProperties properties;
    @MockitoBean private ExerciseAnalysisService analysisService;

    private Member user;
    private Member admin;
    private Category category;
    private Exercise exercise;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        user = memberRepository.saveAndFlush(Member.builder()
                .email("rv-user@test.com").username("rv-u").password("dummy").role(UserRole.USER).build());
        admin = memberRepository.saveAndFlush(Member.builder()
                .email("rv-admin@test.com").username("rv-a").password("dummy").role(UserRole.ADMIN).build());
        category = categoryRepository.saveAndFlush(Category.builder().name("RV-LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder().name("rv-스쿼트").category(category).build());

        userToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(user.getEmail()).role(user.getRole()).build());
        adminToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(admin.getEmail()).role(admin.getRole()).build());

        when(analysisService.isAiReachable(anyLong())).thenReturn(true);
    }

    @AfterEach
    void tearDown() throws IOException {
        exercisesRepository.delete(exercise);
        categoryRepository.delete(category);
        memberRepository.deleteAll(List.of(user, admin));
        FileSystemUtils.deleteRecursively(exerciseDir());
    }

    private Path exerciseDir() {
        return Paths.get(properties.getDir()).resolve(String.valueOf(exercise.getId()));
    }

    private MockMultipartFile mp4(String name) {
        return new MockMultipartFile("file", name, "video/mp4", MP4_HEAD);
    }

    @Test
    @DisplayName("mp4 → 202, 파일이 저장되고 경로가 행에 박히며, AI 에는 ai-dir 을 붙인 경로가 간다")
    void upload_storesFile_commitsPath_thenCallsAi() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/admin/exercises/" + exercise.getId() + "/reference-video")
                        .file(mp4("squat.mp4"))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(exercise.getId()))
                .andExpect(jsonPath("$.referenceVideoPath").isString())
                .andReturn();

        String relative = exercisesRepository.findById(exercise.getId()).orElseThrow().getReferenceVideoPath();
        assertThat(relative).startsWith(exercise.getId() + "/").endsWith(".mp4");
        assertThat(result.getResponse().getContentAsString()).contains(relative);
        assertThat(Files.readAllBytes(Paths.get(properties.getDir()).resolve(relative))).isEqualTo(MP4_HEAD);

        // 테스트 yml 이 ai-dir 을 dir 과 다르게 둔 이유가 이 단언이다.
        verify(analysisService).extractReferencePoses(exercise.getId(), "/data/reference-videos/" + relative);
    }

    @Test
    @DisplayName("재업로드 — 새 파일이 남고 이전 파일은 커밋 뒤 지워진다 (운동당 1개 보관)")
    void reupload_replacesPreviousFile() throws Exception {
        mockMvc.perform(multipart("/admin/exercises/" + exercise.getId() + "/reference-video")
                        .file(mp4("v1.mp4")).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted());
        String first = exercisesRepository.findById(exercise.getId()).orElseThrow().getReferenceVideoPath();

        mockMvc.perform(multipart("/admin/exercises/" + exercise.getId() + "/reference-video")
                        .file(mp4("v2.mp4")).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted());
        String second = exercisesRepository.findById(exercise.getId()).orElseThrow().getReferenceVideoPath();

        assertThat(second).isNotEqualTo(first);
        assertThat(Paths.get(properties.getDir()).resolve(first)).doesNotExist();
        assertThat(Paths.get(properties.getDir()).resolve(second)).exists();
    }

    @Test
    @DisplayName("확장자가 mp4 가 아니면 400(W016) — 아무것도 저장하지 않는다")
    void wrongExtension_returns400() throws Exception {
        mockMvc.perform(multipart("/admin/exercises/" + exercise.getId() + "/reference-video")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", MP4_HEAD))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REFERENCE_VIDEO.getMessage()));

        assertNothingStored();
    }

    @Test
    @DisplayName("이름만 .mp4 이고 파일 머리가 ftyp 가 아니면 400(W016)")
    void wrongMagic_returns400() throws Exception {
        mockMvc.perform(multipart("/admin/exercises/" + exercise.getId() + "/reference-video")
                        .file(new MockMultipartFile("file", "fake.mp4", "video/mp4", "hello world!".getBytes()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REFERENCE_VIDEO.getMessage()));

        assertNothingStored();
    }

    @Test
    @DisplayName("AI 서킷 OPEN 이면 503(W017) — 파일도 행도 그대로다")
    void circuitOpen_returns503_withoutTouchingAnything() throws Exception {
        when(analysisService.isAiReachable(anyLong())).thenReturn(false);

        mockMvc.perform(multipart("/admin/exercises/" + exercise.getId() + "/reference-video")
                        .file(mp4("squat.mp4")).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(ErrorCode.REFERENCE_EXTRACTION_UNAVAILABLE.getMessage()));

        assertNothingStored();
    }

    @Test
    @DisplayName("없는 운동이면 404")
    void unknownExercise_returns404() throws Exception {
        mockMvc.perform(multipart("/admin/exercises/999999/reference-video")
                        .file(mp4("squat.mp4")).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USER 역할이면 403 — 클래스 레벨 @PreAuthorize 가 이 메서드에도 걸린다")
    void userRole_returns403() throws Exception {
        mockMvc.perform(multipart("/admin/exercises/" + exercise.getId() + "/reference-video")
                        .file(mp4("squat.mp4")).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        assertNothingStored();
    }

    /**
     * 크기 상한 초과는 서블릿 컨테이너가 던지는 예외라 MockMvc 로는 재현이 안 된다(요청이 이미 파싱된 채 들어온다).
     * 핸들러의 매핑(413·C007)만 직접 고정한다 — 없으면 500 으로 샌다.
     */
    @Test
    @DisplayName("크기 상한 초과 예외는 413(C007) 로 매핑된다")
    void maxUploadSize_mapsTo413() {
        var response = new GlobalExceptionHandler().handleMaxUploadSize(
                new MaxUploadSizeExceededException(50L * 1024 * 1024), new MockHttpServletRequest("POST", "/x"));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.FILE_TOO_LARGE.getMessage());
    }

    private void assertNothingStored() {
        assertThat(exercisesRepository.findById(exercise.getId()).orElseThrow().getReferenceVideoPath()).isNull();
        assertThat(exerciseDir()).doesNotExist();
        verify(analysisService, never()).extractReferencePoses(anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
