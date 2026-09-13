package com.shadowfit.model.notification;

/**
 * 푸시 토큰이 나온 기기 플랫폼. {@code push_tokens.platform VARCHAR(10)} 에 이름 그대로.
 * Expo Push Service 는 토큰만으로 FCM/APNs 를 고르므로 발송에는 안 쓰인다 — 진단·통계용.
 */
public enum PushPlatform {
    IOS,
    ANDROID
}
