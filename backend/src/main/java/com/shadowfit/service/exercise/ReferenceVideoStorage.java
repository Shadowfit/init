package com.shadowfit.service.exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * 기준 영상 파일 I/O — 트랜잭션 <b>밖</b>의 자원이다.
 *
 * <p>여기엔 DB 도 gRPC 도 없다. 파일은 롤백이 안 되므로 호출자({@link ReferenceVideoService})가 순서와 보상을
 * 맡는다: 먼저 쓰고, DB 가 실패하면 {@link #deleteQuietly} 로 되돌린다.
 *
 * <p>경로 규약 — 루트 아래 {@code {exerciseId}/{uuid}.mp4}. 원래 파일명을 안 쓰는 이유는 경로 조작
 * ({@code ../}) 과 한글·공백 인코딩 문제를 한 번에 없애기 위해서다. 원래 이름은 로그에만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceVideoStorage {

    /** ISO BMFF(mp4) 는 첫 박스가 {@code ftyp} 이고 그 타입 문자열이 4~8바이트에 온다. 확장자만 믿지 않는다. */
    private static final byte[] FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);
    private static final int FTYP_OFFSET = 4;

    private final ReferenceVideoProperties properties;

    /**
     * 검증하고 저장한다.
     *
     * @return 루트 기준 상대 경로 ({@code {exerciseId}/{uuid}.mp4}) — DB 에 들어가는 그 값
     * @throws BusinessException W016 — 비었거나 .mp4 가 아니거나 ftyp 박스가 없다
     */
    public String store(Long exerciseId, MultipartFile file) {
        validate(file);

        String relative = exerciseId + "/" + UUID.randomUUID() + ".mp4";
        Path target = localPath(relative);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("기준 영상 저장 실패: " + target, e);
        }
        log.info("기준 영상 저장 — exerciseId={}, path={}, original={}, bytes={}",
                exerciseId, relative, file.getOriginalFilename(), file.getSize());
        return relative;
    }

    /** 실패해도 던지지 않는다 — 보상·정리 경로에서 부르는데, 여기서 던지면 원래 예외를 덮는다. */
    public void deleteQuietly(String relative) {
        if (relative == null) return;
        try {
            Files.deleteIfExists(localPath(relative));
        } catch (IOException e) {
            log.warn("기준 영상 삭제 실패 (남은 파일은 손으로 지울 것): {}", relative, e);
        }
    }

    /**
     * AI 가 열 수 있는 경로 — gRPC {@code ExtractRequest.youtube_url} 에 실리는 값.
     *
     * <p>{@code ai-dir} 이 있으면(AI 가 컨테이너) 그 아래로 잇되 구분자는 '/' 로 고정한다 — 컨테이너는 리눅스라
     * {@code Paths.get} 을 쓰면 Windows 호스트에서 역슬래시가 섞인다. 없으면(둘 다 같은 파일시스템) <b>절대 경로</b>로
     * 만든다 — {@code dir} 이 상대 경로면 Spring 의 cwd({@code backend/}) 기준인데 AI 의 cwd 는 {@code ai-server/}
     * 라 상대 경로를 그대로 넘기면 엉뚱한 곳을 연다.
     */
    public String aiPath(String relative) {
        String aiDir = properties.getAiDir();
        if (!StringUtils.hasText(aiDir)) {
            return localPath(relative).toAbsolutePath().toString();
        }
        return aiDir.endsWith("/") ? aiDir + relative : aiDir + "/" + relative;
    }

    Path localPath(String relative) {
        return Paths.get(properties.getDir()).resolve(relative).normalize();
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REFERENCE_VIDEO);
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new BusinessException(ErrorCode.INVALID_REFERENCE_VIDEO);
        }
        byte[] head = new byte[FTYP_OFFSET + FTYP.length];
        try (InputStream in = file.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length
                    || !Arrays.equals(head, FTYP_OFFSET, head.length, FTYP, 0, FTYP.length)) {
                throw new BusinessException(ErrorCode.INVALID_REFERENCE_VIDEO);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일 읽기 실패", e);
        }
    }
}
