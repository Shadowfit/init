# ShadowFit Frontend

관절 좌표 기반 AI 자세 분석 피트니스 앱 **ShadowFit**의 React Native(Expo) 클라이언트입니다.

> 이 레포는 [Shadowfit/init](https://github.com/Shadowfit/init) 모노레포의 `frontend/` 모듈이 자동으로 미러링된 것입니다. 백엔드·AI 서버를 포함한 전체 구조와 설계 문서는 모노레포 쪽을 참고하세요.

## 기술 스택

- **React Native (Expo, Expo Router)**, TypeScript
- Zustand (상태 관리), TanStack Query, Axios
- expo-camera, expo-av, expo-speech(TTS), react-native-chart-kit, react-native-calendars

## 실행 방법

```bash
npm install
npx expo start
```

## 주요 기능

- 카메라로 운동 자세 촬영 및 실시간 분석 요청
- 캘린더/리포트 화면에서 세션별 동기화율·구간별 정확도 조회
- TTS 기반 음성 운동 피드백 재생
- JWT 인증 연동 (로그인/온보딩)
