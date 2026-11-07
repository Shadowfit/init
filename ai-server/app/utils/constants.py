"""MediaPipe Pose 랜드마크 인덱스 및 운동별 관절 설정."""

# MediaPipe Pose 33개 랜드마크 인덱스
LANDMARK = {
    "NOSE": 0,
    "LEFT_SHOULDER": 11,
    "RIGHT_SHOULDER": 12,
    "LEFT_ELBOW": 13,
    "RIGHT_ELBOW": 14,
    "LEFT_WRIST": 15,
    "RIGHT_WRIST": 16,
    "LEFT_HIP": 23,
    "RIGHT_HIP": 24,
    "LEFT_KNEE": 25,
    "RIGHT_KNEE": 26,
    "LEFT_ANKLE": 27,
    "RIGHT_ANKLE": 28,
}

# 운동별 주요 관절 각도 정의
# 각 항목: (관절A, 피벗, 관절B) → 피벗을 기준으로 A-피벗-B 각도 계산
EXERCISE_ANGLES = {
    "squat": [
        ("LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"),      # 왼쪽 무릎 각도
        ("RIGHT_HIP", "RIGHT_KNEE", "RIGHT_ANKLE"),    # 오른쪽 무릎 각도
        ("LEFT_SHOULDER", "LEFT_HIP", "LEFT_KNEE"),    # 왼쪽 엉덩이 각도
        ("RIGHT_SHOULDER", "RIGHT_HIP", "RIGHT_KNEE"), # 오른쪽 엉덩이 각도
    ],
    "deadlift": [
        ("LEFT_SHOULDER", "LEFT_HIP", "LEFT_KNEE"),    # 왼쪽 힙 힌지
        ("RIGHT_SHOULDER", "RIGHT_HIP", "RIGHT_KNEE"), # 오른쪽 힙 힌지
        ("LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"),       # 왼쪽 무릎 각도
        ("RIGHT_HIP", "RIGHT_KNEE", "RIGHT_ANKLE"),    # 오른쪽 무릎 각도
    ],
    "pullup": [
        ("LEFT_SHOULDER", "LEFT_ELBOW", "LEFT_WRIST"),   # 왼쪽 팔꿈치 각도
        ("RIGHT_SHOULDER", "RIGHT_ELBOW", "RIGHT_WRIST"), # 오른쪽 팔꿈치 각도
        ("LEFT_HIP", "LEFT_SHOULDER", "LEFT_ELBOW"),      # 왼쪽 어깨 각도
        ("RIGHT_HIP", "RIGHT_SHOULDER", "RIGHT_ELBOW"),   # 오른쪽 어깨 각도
    ],
}

# 싱크로율 판정 임계값의 기본값. sync_rate와 동일하게 0~100 스케일 (2026-10-21 사용자 결정).
#
# 임계값은 **운동마다 한 쌍**이다 — «양호»(good) 이상이면 "자세 양호", «주의»(warn) 이상이면
# "자세 보정 필요", 그 아래는 "즉시 자세 수정 필요". 값은 Spring 이 StartAnalysis/ReattachAnalysis 의
# sync_good_threshold/sync_warn_threshold 로 내려준다. 이 두 상수는 그 값이 안 왔을 때(proto3 0.0)나
# 말이 안 될 때(≤0, >100, warn ≥ good) 쓰는 대체값이다.
#
# ⚠️ 예전에는 페르소나별 표(BEGINNER 60 · ADVANCED 85 · DIET 70 · REHAB 50)와 그 40/70 비율로
#    두 번째 문턱을 만들었다. 이제 페르소나는 트레이너 말투만 정하고 임계값에는 관여하지 않는다.
DEFAULT_SYNC_GOOD = 80.0
DEFAULT_SYNC_WARN = 60.0
