package com.shadowfit.dto.consent;

import com.shadowfit.model.member.ConsentType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 한 회원의 동의 현황 — 앱이 «지금 어떤 동의를 더 받아야 하나» 를 정하는 근거.
 *
 * @param enforced 서버가 동의를 강제하는 중인가(가입·건강정보 입력 시 거절). 꺼져 있으면 기록만 한다.
 * @param current  종류별 현재 문서 버전 — 앱은 이 버전으로 동의를 보낸다.
 * @param agreed   이 회원의 동의 이력(개정 전 버전 포함).
 * @param missing  가입 필수 문서 중 <b>현재 버전</b>에 아직 동의하지 않은 것 — 개정으로 재동의가 필요한 경우도 여기 잡힌다.
 */
@Schema(description = "동의 현황")
public record ConsentStatusDto(
        boolean enforced,
        Map<ConsentType, String> current,
        List<Agreed> agreed,
        List<ConsentType> missing) {

    public record Agreed(ConsentType type, String version, LocalDateTime agreedAt) {}
}
