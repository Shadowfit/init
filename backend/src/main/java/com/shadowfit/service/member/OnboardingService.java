package com.shadowfit.service.member;

import com.shadowfit.dto.onboarding.OnboardingDto;
import com.shadowfit.dto.onboarding.OnboardingRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.member.MemberRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@AllArgsConstructor
public class OnboardingService {
    private final MemberRepository memberRepository;
    private final ConsentService consentService;

    //온보딩 업데이트
    @Transactional
    public OnboardingDto updateOnboarding(String email, OnboardingRequestDto dto){
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));
        // 키·몸무게는 건강에 관한 정보라 일반 동의와 분리해 받는다(HEALTH_INFO). 강제가 꺼져 있으면 통과한다.
        if (dto.getHeight() != null || dto.getWeight() != null) {
            consentService.requireHealthInfo(member.getId());
        }
        member.updateOnboarding(dto);

        // 선호 영상(preferredUrl)은 완료 조건이 아니다(#802). 기준 영상은 서버가 DB 의 기준 좌표로 쓰고 AI 는 이 URL 을 읽지 않는다 —
        // 유튜브 다운로드를 포기한 뒤로 이 필드는 아무 기능도 하지 않으면서 «아무 영상이나 골라야 온보딩이 끝나는» 관문이었다.
        if (member.getSelectedPersona() != null &&
                member.getWorkoutLevel() != null &&
                member.getHeight() != null &&
                member.getWeight() != null) {
            member.completeOnboarding();
        }
        return OnboardingDto.fromEntity(member);
    }

    //온보딩 업데이트
    @Transactional(readOnly = true)
    public OnboardingDto readOnboarding(String email){
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        return OnboardingDto.fromEntity(member);
    }

}
