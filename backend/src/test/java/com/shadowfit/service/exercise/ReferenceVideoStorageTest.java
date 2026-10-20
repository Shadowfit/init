package com.shadowfit.service.exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReferenceVideoStorage — 검증·경로 규약")
class ReferenceVideoStorageTest {

    private static final byte[] MP4_HEAD = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    @TempDir Path tmp;

    private ReferenceVideoStorage storage(String aiDir) {
        ReferenceVideoProperties p = new ReferenceVideoProperties();
        p.setDir(tmp.toString());
        p.setAiDir(aiDir);
        return new ReferenceVideoStorage(p);
    }

    @Test
    @DisplayName("저장 — {exerciseId}/{uuid}.mp4 로 놓이고 원래 이름은 경로에 안 들어간다")
    void store_layout() throws Exception {
        String rel = storage(null).store(7L, new MockMultipartFile("file", "../evil name.mp4", "video/mp4", MP4_HEAD));

        assertThat(rel).matches("7/[0-9a-f-]{36}[.]mp4");
        assertThat(Files.readAllBytes(tmp.resolve(rel))).isEqualTo(MP4_HEAD);
    }

    @Test
    @DisplayName("ai-dir 이 있으면 '/' 로 잇고, 없으면 dir 의 절대 경로 — 상대 경로를 AI 에 넘기지 않는다")
    void aiPath_modes() {
        assertThat(storage("/data/reference-videos").aiPath("7/x.mp4")).isEqualTo("/data/reference-videos/7/x.mp4");
        assertThat(storage("/data/reference-videos/").aiPath("7/x.mp4")).isEqualTo("/data/reference-videos/7/x.mp4");

        String same = storage("").aiPath("7/x.mp4");
        assertThat(Path.of(same)).isAbsolute().isEqualTo(tmp.resolve("7/x.mp4").toAbsolutePath());
    }

    @Test
    @DisplayName("빈 파일·다른 확장자·ftyp 아님 → W016")
    void validate_rejects() {
        ReferenceVideoStorage s = storage(null);
        assertRejected(s, new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[0]));
        assertRejected(s, new MockMultipartFile("file", "a.mov", "video/quicktime", MP4_HEAD));
        assertRejected(s, new MockMultipartFile("file", "a.mp4", "video/mp4", "not a video".getBytes()));
        assertRejected(s, new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{0, 0, 0, 24, 'f', 't'}));
    }

    private void assertRejected(ReferenceVideoStorage s, MockMultipartFile f) {
        assertThatThrownBy(() -> s.store(1L, f))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REFERENCE_VIDEO);
        assertThat(tmp.resolve("1")).doesNotExist();
    }
}
