package com.shadowfit.model.member;

/**
 * 동의를 받는 문서의 종류. DB 에는 이름(문자열)으로 저장된다({@code member_consents.consent_type}).
 */
public enum ConsentType {

    /** 이용약관 — 가입 필수. */
    TERMS(true),

    /** 개인정보 수집·이용 — 가입 필수. */
    PRIVACY(true),

    /**
     * 건강정보(키·몸무게·운동 수준) 수집·이용. 일반 동의와 <b>분리</b>한다 — 온보딩에서 몸 정보를 받기 직전에 따로 받고,
     * 가입 때는 받지 않는다. 건강에 관한 정보는 일반 개인정보보다 엄격하게 다뤄야 할 수 있어(민감정보 해당 여부는 법률 확인 항목)
     * 한 번의 포괄 동의에 섞지 않는다.
     */
    HEALTH_INFO(false);

    private final boolean requiredAtSignup;

    ConsentType(boolean requiredAtSignup) {
        this.requiredAtSignup = requiredAtSignup;
    }

    /** 가입할 때 반드시 동의해야 하는가. */
    public boolean isRequiredAtSignup() {
        return requiredAtSignup;
    }
}
