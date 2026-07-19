package com.shadowfit.service.Member;

import com.shadowfit.dto.login.*;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtBlacklist;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.RefreshToken;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.member.RefreshTokenRepository;
import com.shadowfit.service.Exercise.PoseDataCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService{
    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRepository sessionRepository;
    private final PoseDataCleanupService poseDataCleanupService;
    private final PasswordEncoder passwordEncoder;
    private final JwtBlacklist jwtBlacklist;
    //로그인 로직
    @Transactional
    public LoginResponseDto login(LoginRequestDto dto){
        Member member = memberRepository.findByEmail(dto.getEmail()).
                orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if(!passwordEncoder.matches(dto.getPassword(), member.getPassword())){
            throw new BusinessException(ErrorCode.LOGIN_INPUT_INVALID);
        }

        CustomUserInfoDto info = CustomUserInfoDto.builder()
                .email(member.getEmail())
                .role(member.getRole())
                .build();

        String accessToken=jwtUtil.createAccessToken(info);
        String refreshToken=jwtUtil.createRefreshToken(info);
        UserRole role = member.getRole();

        RefreshToken refreshTokenEntity= RefreshToken.builder()
                .memberId(member.getId())
                .token(refreshToken)
                .build();
        refreshTokenRepository.save(refreshTokenEntity);
        return new LoginResponseDto(accessToken,refreshToken,role);
    }

    //로그아웃 로직
    @Transactional
    public void logout(LogOutRequestDto dto){
        refreshTokenRepository.deleteByToken(dto.getRefreshToken());
        String token = dto.getAccessToken();
        if(token.startsWith("Bearer ")){
            token = token.substring(7);
        }
        long expiration = jwtUtil.getExpiration(token);
        jwtBlacklist.add(token,expiration);
    }

    //회원가입 로직
    @Transactional
    public String signup(MemberRequestDto dto) {
        if(memberRepository.existsByEmail((dto.getEmail()))) {
            throw new BusinessException(ErrorCode.USERID_DUPLICATION);
        }
        String encodedPassword = passwordEncoder.encode(dto.getPassword());
        Member member = Member.builder()
                .username(dto.getUsername())
                .email(dto.getEmail())
                .password(encodedPassword)
                .role(dto.getRole())
                .build();
        memberRepository.save(member);
        return member.getUsername();
    }

    //회원탈퇴 로직
    @Transactional
    public void deleteAccount(String email){
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // pose_data는 파티셔닝을 위해 FK(ON DELETE CASCADE)를 제거했음 — 세션이 지워지기 전에
        // session_id 목록을 미리 확보해둬야 afterCommit 이후 정리할 수 있음
        // (docs/decisions/pose-data-partition-fk-tradeoff.md).
        List<Long> sessionIds = sessionRepository.findIdsByMemberId(member.getId());

        // refresh_token.member_id 는 users(id) ON DELETE CASCADE 라 DB가 자동으로 같이 지움 —
        // 수동으로도 지우면 이미 사라진 행을 또 지우려다 StaleStateException(0 row) 발생.
        // exercise_sessions.member_id 도 동일하게 CASCADE 유지(FK 그대로) — pose_data만 예외.
        memberRepository.delete(member);

        // afterCommit: 탈퇴 트랜잭션이 확정된 직후, 스케줄 대기 없이 즉시 비동기로 pose_data 정리
        // 트리거 (개인정보보호법 제21조 "지체없이" 파기 요건 대응, 탈퇴 API 응답은 기다리지 않음).
        if (!sessionIds.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            poseDataCleanupService.cleanupBySessionIds(sessionIds);
                        }
                    }
            );
        }
    }
}
