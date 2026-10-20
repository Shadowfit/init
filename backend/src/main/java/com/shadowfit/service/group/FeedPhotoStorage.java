package com.shadowfit.service.group;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 피드 공유 사진 저장소 — 로컬 디렉터리(compose 는 bind mount). {@code ReferenceVideoStorage} 와 같은 방식이고,
 * 같은 전제(단일 호스트)를 진다 — 서버가 둘이 되면 오브젝트 스토리지로 바꿔야 한다.
 *
 * <p>확장자가 아니라 <b>파일 앞 바이트</b>로 형식을 판정한다 — 이름만 .jpg 인 아무 파일이 정적 경로로
 * 서빙되는 걸 막기 위해서다. 저장 이름은 UUID 라 경로 조작·추측이 안 된다.
 */
@Slf4j
@Component
public class FeedPhotoStorage {

    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("^[0-9a-f\\-]{36}\\.(jpg|png|webp)$");

    private final Path root;

    public FeedPhotoStorage(@Value("${feed-photo.dir:./data/feed-photos}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** 저장하고 파일 이름(UUID.확장자)을 돌려준다. */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_FEED_PHOTO);
        }
        String ext;
        try (InputStream in = file.getInputStream()) {
            ext = sniff(in.readNBytes(12)).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_FEED_PHOTO));
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일 읽기 실패", e);
        }
        String name = UUID.randomUUID() + "." + ext;
        Path target = root.resolve(name);
        try {
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("피드 사진 저장 실패: " + target, e);
        }
        log.info("피드 사진 저장 — name={}, bytes={}", name, file.getSize());
        return name;
    }

    /** 이름이 우리가 만든 형식이고 파일이 있으면 그 경로. 아니면 empty — 경로 조작(../)은 형식 검사에서 걸린다. */
    public Optional<Path> resolve(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Path p = root.resolve(name).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    public void deleteQuietly(String name) {
        resolve(name).ifPresent(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("피드 사진 삭제 실패 (남은 파일은 손으로 지울 것): {}", name, e);
            }
        });
    }

    public static String contentType(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    static Optional<String> sniff(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return Optional.of("jpg");
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        if (h.length >= 4 && Arrays.equals(h, 0, 4, png, 0, 4)) {
            return Optional.of("png");
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return Optional.of("webp");
        }
        return Optional.empty();
    }
}
