package com.shadowfit.service.group;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 모임 코드 참여용 초대 코드를 만든다 (docs/decisions/social-cheer-and-group-feed.md §3-F).
 *
 * <p><b>8자리, 32자 알파벳.</b> 대문자+숫자 36자에서 서로 헷갈리는 0/O, 1/I 를 뺐다 — 이 값은
 * 카톡으로 건네받아 «코드로 참여» 입력란에 <b>손으로</b> 치는 것이라 읽기 오류가 곧 참여
 * 실패다. 32⁸ ≈ 1.1×10¹² 이므로 그룹 수가 수만 개여도 충돌은 무시 가능하다. 길이의 근거는
 * 「손 입력 가능」과 「그룹 수 대비 충돌 무시 가능」 둘이고, 그 이상 늘릴 이유가 없다.
 *
 * <p>충돌은 확률로 없애는 게 아니라 {@code uk_workout_groups_invite_code} 가 최종 방어선이다 —
 * 호출자({@link GroupService})가 저장 전 존재 확인을 하고, 그 틈의 레이스는 UNIQUE 위반으로
 * 잡아 한 번 더 뽑는다.
 *
 * <p>{@link SecureRandom} 을 쓰는 이유: 이 코드를 아는 사람은 승인 없이 모임에 들어온다(3-F 결정).
 * 순차·시드 예측이 가능하면 남의 모임 코드를 맞힐 수 있으므로 {@code SessionNonceGenerator}
 * 와 같은 이유로 예측 불가 난수원을 쓴다. 인스턴스는 재사용한다(재시드 비용, thread-safe).
 */
@Component
public class InviteCodeGenerator {

    /** 대문자+숫자에서 0/O, 1/I 제외 — 32자. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }
}
