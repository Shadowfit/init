package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.grpc.ExtractRequest;
import com.shadowfit.grpc.ExtractResponse;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.service.exercise.ExerciseGrpcService;
import io.grpc.stub.StreamObserver;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 웹이 새로 쓰는 API — 회원 상세 · 모임 · 공유 글 검수 · 대시보드 추이/커뮤니티.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_console;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        "grpc.server.port=-1",
        "feed-photo.dir=build/test-admin-feed-photos"
})
@DisplayName("관리자 콘솔 — 회원 상세 · 모임 · 공유 글 · 대시보드")
class AdminConsoleIntegrationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    // AI → Spring 기준 좌표 역호출(ExtractReferenceData)의 수신부 — gRPC 서버 없이 직접 부른다
    @Autowired private ExerciseGrpcService grpcService;

    private Member admin, jiwoo, minho;
    private Category category;
    private Exercise squat;

    @BeforeEach
    void setUp() {
        admin = save("콘솔관리자", UserRole.ADMIN);
        jiwoo = save("콘솔지우", UserRole.USER);
        minho = save("콘솔민호", UserRole.USER);
        category = categoryRepository.saveAndFlush(Category.builder().name("CONSOLE_LOWER").build());
        squat = exercisesRepository.saveAndFlush(Exercise.builder().name("스쿼트").category(category)
                .expectedDurationMinutes(15)
                .analysisSupported(true).build());
    }

    @AfterEach
    void tearDown() {
        List<Long> ids = List.of(admin.getId(), jiwoo.getId(), minho.getId());
        for (Long id : ids) {
            jdbcTemplate.update("DELETE FROM workout_groups WHERE created_by = ?", id);
            jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id = ?", id);
        }
        memberRepository.deleteAll(List.of(admin, jiwoo, minho));
        jdbcTemplate.update("DELETE FROM exercise_references WHERE exercise_id = ?", squat.getId());
        exercisesRepository.delete(squat);
        categoryRepository.delete(category);
    }

    @Test
    @DisplayName("3D 시범 기준 좌표 — PUT 으로 교체 · GET 요약 · 일반 회원 403 · 깨진 JSON 400")
    void referencePoses() throws Exception {
        String marker = "{\"index\":-1,\"x\":0,\"y\":0,\"z\":0,\"visibility\":0,\"image_aspect_ratio\":1.0,\"source\":\"mannequin-3d-joints\"}";
        StringBuilder frames = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) frames.append(',');
            frames.append("{\"timestampSec\":").append(i * 0.1).append(",\"jointCoordinates\":\"")
                    .append(("[{\"index\":25,\"x\":0.5,\"y\":0.6,\"z\":0,\"visibility\":1}," + marker + "]").replace("\"", "\\\""))
                    .append("\"}");
        }
        String body = "{\"source\":\"MANNEQUIN_3D\",\"frames\":[" + frames + "]}";

        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), jiwoo).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), admin).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameCount").value(5))
                .andExpect(jsonPath("$.source").value("MANNEQUIN_3D"))
                .andExpect(jsonPath("$.durationSec").value(0.4));
        // 두 번 올려도 «추가» 가 아니라 «교체» — 5개 그대로
        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), admin).content(body))
                .andExpect(jsonPath("$.frameCount").value(5));
        mockMvc.perform(auth(get("/admin/exercises/" + squat.getId() + "/reference"), admin))
                .andExpect(jsonPath("$.frameCount").value(5))
                .andExpect(jsonPath("$.source").value("MANNEQUIN_3D"))
                // V34 — 직접 등록은 그 자리에서 READY. 무릎 랜드마크가 25 하나뿐이라 각도는 못 잰다(null)
                .andExpect(jsonPath("$.extractionStatus").value("READY"))
                .andExpect(jsonPath("$.extractionError").doesNotExist())
                .andExpect(jsonPath("$.extractionAt").isString())
                .andExpect(jsonPath("$.minKneeDeg").doesNotExist())
                .andExpect(jsonPath("$.depthVerdict").doesNotExist());
        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), admin)
                        .content("{\"source\":\"MANNEQUIN_3D\",\"frames\":[{\"timestampSec\":0,\"jointCoordinates\":\"x\"},{\"timestampSec\":0.1,\"jointCoordinates\":\"x\"},{\"timestampSec\":0.2,\"jointCoordinates\":\"x\"},{\"timestampSec\":0.3,\"jointCoordinates\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("기준 깊이 — 무릎 최솟값 90° 면 OK, 120° 면 LENIENT, 45° 면 STRICT (AI 와 같은 2D 각도)")
    void referenceDepthVerdict() throws Exception {
        // 등록 전 — 시도 없음
        mockMvc.perform(auth(get("/admin/exercises/" + squat.getId() + "/reference"), admin))
                .andExpect(jsonPath("$.frameCount").value(0))
                .andExpect(jsonPath("$.extractionStatus").value("NONE"))
                .andExpect(jsonPath("$.extractionAt").doesNotExist())
                .andExpect(jsonPath("$.minKneeDeg").doesNotExist());

        // 서 있다(170°) → 바닥 90° → 다시 선다. 고관절은 매 프레임 90°(어깨를 엉덩이 옆에 둔다)
        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), admin)
                        .content(depthBody(170, 130, 90, 130, 170)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minKneeDeg").value(90.0))
                .andExpect(jsonPath("$.minHipDeg").value(90.0))
                .andExpect(jsonPath("$.depthVerdict").value("OK"))
                .andExpect(jsonPath("$.extractionStatus").value("READY"));

        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), admin)
                        .content(depthBody(170, 150, 120, 150, 170)))
                .andExpect(jsonPath("$.minKneeDeg").value(120.0))
                .andExpect(jsonPath("$.depthVerdict").value("LENIENT"));
        mockMvc.perform(auth(get("/admin/exercises/" + squat.getId() + "/reference"), admin))
                .andExpect(jsonPath("$.minKneeDeg").value(120.0))
                .andExpect(jsonPath("$.depthVerdict").value("LENIENT"));

        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), admin)
                        .content(depthBody(170, 100, 45, 100, 170)))
                .andExpect(jsonPath("$.minKneeDeg").value(45.0))
                .andExpect(jsonPath("$.depthVerdict").value("STRICT"));
    }

    @Test
    @DisplayName("기준 등록 상태 — AI 역호출의 실패 사유는 FAILED 로 남고 기존 기준은 그대로, 성공은 READY, 10분 넘은 EXTRACTING 은 FAILED 로 보인다")
    void referenceExtractionStatus() throws Exception {
        String reference = "/admin/exercises/" + squat.getId() + "/reference";
        mockMvc.perform(json(put("/admin/exercises/" + squat.getId() + "/reference-poses"), admin)
                        .content(depthBody(170, 130, 90, 130, 170)))
                .andExpect(status().isOk());

        // 1) AI 가 추출 실패를 역호출로 알린다 — 좌표는 비어 오고 error_message 만 있다
        @SuppressWarnings("unchecked")
        StreamObserver<ExtractResponse> failedObs = mock(StreamObserver.class);
        grpcService.extractReferenceData(ExtractRequest.newBuilder()
                .setExerciseId(squat.getId()).setErrorMessage("영상에서 사람을 찾지 못했어요.").build(), failedObs);
        verify(failedObs).onCompleted();
        mockMvc.perform(auth(get(reference), admin))
                .andExpect(jsonPath("$.extractionStatus").value("FAILED"))
                .andExpect(jsonPath("$.extractionError").value("영상에서 사람을 찾지 못했어요."))
                // 실패한 재등록이 멀쩡한 기준을 지우지 않는다 — 앞서 등록한 5프레임·90° 그대로
                .andExpect(jsonPath("$.frameCount").value(5))
                .andExpect(jsonPath("$.source").value("MANNEQUIN_3D"))
                .andExpect(jsonPath("$.minKneeDeg").value(90.0));

        // 2) 성공 역호출 — 좌표가 교체되고 READY
        ExtractRequest.Builder ok = ExtractRequest.newBuilder().setExerciseId(squat.getId());
        double[] knees = {160, 100, 80, 100, 160};
        for (int i = 0; i < knees.length; i++) {
            ok.addExtractedPoses(PoseDataRequest.newBuilder()
                    .setTimestampSec(i * 0.2).setJointCoordinates(landmarks(knees[i], false)).build());
        }
        @SuppressWarnings("unchecked")
        StreamObserver<ExtractResponse> okObs = mock(StreamObserver.class);
        grpcService.extractReferenceData(ok.build(), okObs);
        verify(okObs).onCompleted();
        mockMvc.perform(auth(get(reference), admin))
                .andExpect(jsonPath("$.extractionStatus").value("READY"))
                .andExpect(jsonPath("$.extractionError").doesNotExist())
                .andExpect(jsonPath("$.frameCount").value(5))
                .andExpect(jsonPath("$.source").value("VIDEO"))
                .andExpect(jsonPath("$.minKneeDeg").value(80.0))
                .andExpect(jsonPath("$.depthVerdict").value("OK"));

        // 3) 추출 중인데 AI 가 10분 넘게 말이 없다 — 저장값은 EXTRACTING 이지만 FAILED 로 보인다
        jdbcTemplate.update("UPDATE exercises SET reference_extraction_status = 'EXTRACTING', "
                        + "reference_extraction_error = NULL, reference_extraction_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(11), squat.getId());
        mockMvc.perform(auth(get(reference), admin))
                .andExpect(jsonPath("$.extractionStatus").value("FAILED"))
                .andExpect(jsonPath("$.extractionError").value("AI 응답이 10분 넘게 없어요. 영상을 다시 올려주세요."));
        assertThat(jdbcTemplate.queryForObject("SELECT reference_extraction_status FROM exercises WHERE id = ?",
                String.class, squat.getId())).isEqualTo("EXTRACTING");

        // 대조군 — 1분 전이면 아직 기다리는 중
        jdbcTemplate.update("UPDATE exercises SET reference_extraction_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), squat.getId());
        mockMvc.perform(auth(get(reference), admin))
                .andExpect(jsonPath("$.extractionStatus").value("EXTRACTING"))
                .andExpect(jsonPath("$.extractionError").doesNotExist());
    }

    @Test
    @DisplayName("판정 기준 PATCH — {good, warn} 로 바꾸면 상세에 반영, 0 < warn < good ≤ 100 위반은 400(W022), 값이 빠지면 400")
    void thresholds() throws Exception {
        String path = "/admin/exercises/" + squat.getId() + "/thresholds";
        mockMvc.perform(auth(get("/admin/exercises/" + squat.getId()), admin))
                .andExpect(jsonPath("$.syncGoodThreshold").value(80.0))
                .andExpect(jsonPath("$.syncWarnThreshold").value(60.0))
                .andExpect(jsonPath("$.syncThresholdBeginner").doesNotExist());

        mockMvc.perform(json(patch(path), jiwoo).content("{\"good\":85,\"warn\":55}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(patch(path), admin).content("{\"good\":85,\"warn\":55}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exerciseId").value(squat.getId()))
                .andExpect(jsonPath("$.syncGoodThreshold").value(85.0))
                .andExpect(jsonPath("$.syncWarnThreshold").value(55.0))
                .andExpect(jsonPath("$.name").doesNotExist());
        mockMvc.perform(auth(get("/admin/exercises/" + squat.getId()), admin))
                .andExpect(jsonPath("$.syncGoodThreshold").value(85.0))
                .andExpect(jsonPath("$.syncWarnThreshold").value(55.0));

        for (String bad : List.of("{\"good\":60,\"warn\":80}", "{\"good\":70,\"warn\":70}",
                "{\"good\":80,\"warn\":0}", "{\"good\":101,\"warn\":60}")) {
            mockMvc.perform(json(patch(path), admin).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_SYNC_THRESHOLDS.getMessage()));
        }
        mockMvc.perform(json(patch(path), admin).content("{\"good\":80}"))
                .andExpect(status().isBadRequest());
        // 거부된 요청은 아무것도 안 바꾼다
        mockMvc.perform(auth(get("/admin/exercises/" + squat.getId()), admin))
                .andExpect(jsonPath("$.syncGoodThreshold").value(85.0))
                .andExpect(jsonPath("$.syncWarnThreshold").value(55.0));
    }

    /**
     * 3D 시범 등록 본문 — 무릎각이 {@code kneesDeg} 순서로 변하는 프레임들(0.1초 간격).
     */
    private String depthBody(double... kneesDeg) throws Exception {
        List<java.util.Map<String, Object>> frames = new java.util.ArrayList<>();
        for (int i = 0; i < kneesDeg.length; i++) {
            frames.add(java.util.Map.of("timestampSec", i * 0.1, "jointCoordinates", landmarks(kneesDeg[i], true)));
        }
        return new ObjectMapper().writeValueAsString(java.util.Map.of("source", "MANNEQUIN_3D", "frames", frames));
    }

    /**
     * 좌우 대칭 랜드마크 JSON — 엉덩이(23·24)·무릎(25·26)·발목(27·28)이 무릎에서 {@code kneeDeg} 를 이루고,
     * 어깨(11·12)를 엉덩이 옆에 둬서 고관절(11-23-25)은 90° 다. 화면 비율 1.0(보정 없음).
     * 무릎 위 벡터가 (0,-0.2) 이고 발목 쪽 벡터를 그 방향에서 kneeDeg 만큼 돌린다.
     */
    private String landmarks(double kneeDeg, boolean mannequin) {
        double rad = Math.toRadians(kneeDeg);
        double ankleX = 0.5 + 0.2 * Math.sin(rad), ankleY = 0.6 - 0.2 * Math.cos(rad);
        StringBuilder sb = new StringBuilder("[");
        for (int side = 0; side < 2; side++) {
            sb.append(point(11 + side, 0.3, 0.4)).append(',')
                    .append(point(23 + side, 0.5, 0.4)).append(',')
                    .append(point(25 + side, 0.5, 0.6)).append(',')
                    .append(point(27 + side, ankleX, ankleY)).append(',');
        }
        sb.append("{\"index\":-1,\"x\":0,\"y\":0,\"z\":0,\"visibility\":0,\"image_aspect_ratio\":1.0")
                .append(mannequin ? ",\"source\":\"mannequin-3d-joints\"" : "").append("}]");
        return sb.toString();
    }

    private String point(int index, double x, double y) {
        return String.format(java.util.Locale.ROOT,
                "{\"index\":%d,\"x\":%.6f,\"y\":%.6f,\"z\":0,\"visibility\":1}", index, x, y);
    }

    @Test
    @DisplayName("일반 회원은 새 관리자 API 전부 403")
    void nonAdminForbidden() throws Exception {
        for (String path : List.of("/admin/members/" + minho.getId(), "/admin/groups", "/admin/feed-posts",
                "/admin/stats/daily", "/admin/stats/community")) {
            mockMvc.perform(auth(get(path), jiwoo)).andExpect(status().isForbidden());
        }
        mockMvc.perform(auth(delete("/admin/groups/1"), jiwoo)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("회원 상세 · 모임 목록/상세 · 공유 글 검수/삭제 · 모임 삭제 · 대시보드 추이/커뮤니티")
    void consoleReads() throws Exception {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        Session done = sessionRepository.saveAndFlush(Session.builder().member(jiwoo).exercise(squat)
                .referenceSource("https://youtu.be/dummy").startTime(start).endTime(start.plusMinutes(12))
                .status(Status.COMPLETED).totalReps(15).avgSyncRate(new BigDecimal("82.50")).difficultyLevel(1).build());

        // 모임 + 공유 글(사진)
        String g = mockMvc.perform(json(post("/groups"), jiwoo).content("{\"name\":\"새벽 스쿼트단\"}"))
                .andReturn().getResponse().getContentAsString();
        long groupId = JsonPath.parse(g).read("$.id", Long.class);
        mockMvc.perform(json(post("/groups/join"), minho)
                .content("{\"inviteCode\":\"" + JsonPath.parse(g).read("$.inviteCode", String.class) + "\"}"));
        mockMvc.perform(auth(multipart("/groups/" + groupId + "/shares")
                        .file(new MockMultipartFile("photo", "a.jpg", "image/jpeg", JPEG))
                        .param("sessionId", String.valueOf(done.getId())).param("caption", "오늘도 완주"), jiwoo))
                .andExpect(status().isCreated());

        mockMvc.perform(auth(get("/admin/members/" + jiwoo.getId()), admin))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.sessionCount").value(1))
                .andExpect(jsonPath("$.completedSessionCount").value(1))
                .andExpect(jsonPath("$.averageSyncRate").value(82.5))
                .andExpect(jsonPath("$.groups[0].name").value("새벽 스쿼트단"))
                .andExpect(jsonPath("$.groups[0].role").value("OWNER"))
                .andExpect(jsonPath("$.recentSessions[0].exerciseName").value("스쿼트"));

        mockMvc.perform(auth(get("/admin/groups").param("keyword", "스쿼트"), admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ownerUsername").value("콘솔지우"))
                .andExpect(jsonPath("$.content[0].activeMemberCount").value(2))
                .andExpect(jsonPath("$.content[0].sharedPostCount").value(1));
        mockMvc.perform(auth(get("/admin/groups/" + groupId), admin))
                .andExpect(jsonPath("$.members", hasSize(2)));

        String posts = mockMvc.perform(auth(get("/admin/feed-posts"), admin))
                .andExpect(jsonPath("$.content[0].caption").value("오늘도 완주"))
                .andExpect(jsonPath("$.content[0].authorUsername").value("콘솔지우"))
                .andReturn().getResponse().getContentAsString();
        long postId = JsonPath.parse(posts).read("$.content[0].postId", Long.class);

        mockMvc.perform(auth(get("/admin/stats/daily").param("days", "7"), admin))
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[6].date").value(LocalDate.now().toString()));
        mockMvc.perform(auth(get("/admin/stats/community"), admin))
                .andExpect(jsonPath("$.sharedPostCount").value(1));

        mockMvc.perform(auth(delete("/admin/feed-posts/" + postId), admin)).andExpect(status().isNoContent());
        mockMvc.perform(auth(delete("/admin/feed-posts/" + postId), admin)).andExpect(status().isNotFound());
        mockMvc.perform(auth(delete("/admin/groups/" + groupId), admin)).andExpect(status().isNoContent());
        mockMvc.perform(auth(get("/admin/groups/" + groupId), admin)).andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req, Member who) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(who.getEmail()).role(who.getRole()).build();
        return req.header("Authorization", "Bearer " + jwtUtil.createAccessToken(info));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, Member who) {
        return auth(req, who).contentType(MediaType.APPLICATION_JSON);
    }

    private Member save(String username, UserRole role) {
        return memberRepository.saveAndFlush(Member.builder().email(username + "@console.test").username(username)
                .password(passwordEncoder.encode("password123")).selectedPersona(SelectedPersona.BEGINNER)
                .role(role).build());
    }
}
