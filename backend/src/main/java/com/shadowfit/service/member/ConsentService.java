package com.shadowfit.service.member;

import com.shadowfit.dto.consent.ConsentItemDto;
import com.shadowfit.dto.consent.ConsentStatusDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.ConsentType;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.MemberConsent;
import com.shadowfit.repository.member.MemberConsentRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 약관·개인정보·건강정보 동의의 기록과 판정 (V36, docs/tasks/39 §A-1).
 *
 * <p><b>강제는 설정 하나({@code consent.enforce})로 켠다 — 기본은 꺼짐이다.</b> 이 서비스와 API 가 먼저 들어가고,
 * 앱이 동의 화면을 갖춘 뒤에 켠다. 먼저 켜면 동의 필드를 모르는 현재 앱의 가입이 전부 거절되고, 온보딩에서 키·몸무게를
 * 보내는 요청도 막힌다. 꺼져 있어도 <b>기록은 한다</b> — 새 앱이 동의를 보내면 그대로 쌓인다.
 *
 * <p>문서 버전은 설정({@code consent.version.*})이다. 처리방침이 개정되면 코드가 아니라 설정의 버전을 올리고, 그러면
 * {@link #status}의 {@code missing} 에 다시 잡혀 앱이 재동의를 받는다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ConsentService {

    private final MemberConsentRepository consentRepository;
    private final MemberRepository memberRepository;
    private final boolean enforce;
    private final Map<ConsentType, String> currentVersions = new EnumMap<>(ConsentType.class);

    public ConsentService(MemberConsentRepository consentRepository,
                          MemberRepository memberRepository,
                          @Value("${consent.enforce:false}") boolean enforce,
                          @Value("${consent.version.terms:v1}") String termsVersion,
                          @Value("${consent.version.privacy:v1}") String privacyVersion,
                          @Value("${consent.version.health-info:v1}") String healthInfoVersion) {
        this.consentRepository = consentRepository;
        this.memberRepository = memberRepository;
        this.enforce = enforce;
        currentVersions.put(ConsentType.TERMS, termsVersion);
        currentVersions.put(ConsentType.PRIVACY, privacyVersion);
        currentVersions.put(ConsentType.HEALTH_INFO, healthInfoVersion);
    }

    public boolean isEnforced() {
        return enforce;
    }

    /**
     * 가입 요청이 가입 필수 문서(이용약관·개인정보)의 <b>현재 버전</b>에 동의했는가. 강제가 꺼져 있으면 아무것도 하지 않는다.
     *
     * @throws BusinessException CONSENT_REQUIRED — 빠진 문서가 있을 때 / CONSENT_VERSION_MISMATCH — 옛 버전일 때
     */
    public void requireAtSignup(List<ConsentItemDto> items) {
        if (!enforce) {
            return;
        }
        validateVersions(items);
        for (ConsentType type : ConsentType.values()) {
            if (type.isRequiredAtSignup() && !contains(items, type)) {
                throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
            }
        }
    }

    /** 건강정보 동의 없이는 몸 정보(키·몸무게)를 받지 않는다. 강제가 꺼져 있으면 통과. */
    public void requireHealthInfo(Long memberId) {
        if (!enforce) {
            return;
        }
        if (!consentRepository.existsByMemberIdAndConsentTypeAndVersion(
                memberId, ConsentType.HEALTH_INFO, currentVersions.get(ConsentType.HEALTH_INFO))) {
            throw new BusinessException(ErrorCode.CONSENT_HEALTH_REQUIRED);
        }
    }

    /**
     * 동의를 기록한다. 같은 (회원, 종류, 버전)은 한 번만 — 다시 보내도 행이 늘지 않는다(멱등).
     * 비어 있으면 아무것도 하지 않는다 — 강제가 꺼진 동안 동의를 모르는 앱의 가입이 여기로 온다.
     */
    @Transactional
    public void record(Member member, List<ConsentItemDto> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        validateVersions(items);
        LocalDateTime now = LocalDateTime.now();
        for (ConsentItemDto item : items) {
            if (consentRepository.existsByMemberIdAndConsentTypeAndVersion(
                    member.getId(), item.getType(), item.getVersion())) {
                continue;
            }
            consentRepository.save(MemberConsent.builder()
                    .member(member).consentType(item.getType()).version(item.getVersion()).agreedAt(now).build());
        }
    }

    /** 로그인한 회원의 동의를 기록하고 현황을 돌려준다. */
    @Transactional
    public ConsentStatusDto record(Long memberId, List<ConsentItemDto> items) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        record(member, items);
        return status(memberId);
    }

    public ConsentStatusDto status(Long memberId) {
        List<MemberConsent> history = consentRepository.findAllByMemberIdOrderByAgreedAtAsc(memberId);
        List<ConsentStatusDto.Agreed> agreed = new ArrayList<>();
        for (MemberConsent c : history) {
            agreed.add(new ConsentStatusDto.Agreed(c.getConsentType(), c.getVersion(), c.getAgreedAt()));
        }
        List<ConsentType> missing = new ArrayList<>();
        for (ConsentType type : ConsentType.values()) {
            if (!type.isRequiredAtSignup()) {
                continue;
            }
            boolean ok = history.stream().anyMatch(c ->
                    c.getConsentType() == type && c.getVersion().equals(currentVersions.get(type)));
            if (!ok) {
                missing.add(type);
            }
        }
        return new ConsentStatusDto(enforce, new EnumMap<>(currentVersions), agreed, missing);
    }

    /** 보낸 항목이 전부 «현재 버전» 인가 — 개정된 문서에 옛 버전으로 동의했다는 기록을 만들지 않는다. */
    private void validateVersions(List<ConsentItemDto> items) {
        if (items == null) {
            return;
        }
        for (ConsentItemDto item : items) {
            String current = currentVersions.get(item.getType());
            if (!current.equals(item.getVersion())) {
                log.info("동의 버전 불일치 — type={}, 받은={}, 현재={}", item.getType(), item.getVersion(), current);
                throw new BusinessException(ErrorCode.CONSENT_VERSION_MISMATCH);
            }
        }
    }

    private static boolean contains(List<ConsentItemDto> items, ConsentType type) {
        return items != null && items.stream().anyMatch(i -> i.getType() == type);
    }
}
