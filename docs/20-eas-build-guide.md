# Dev Client + EAS Build 가이드 (팀 공유용 개발 빌드)

이 문서는 ShadowFit 프로젝트에서 **Expo Go의 한계를 벗어나야 할 때** 사용하는 가이드입니다.
예: `react-native-vision-camera` 같은 비-Expo 네이티브 모듈이 필요할 때.

---

## 목차
1. [언제 이 가이드가 필요한가](#1-언제-이-가이드가-필요한가)
2. [핵심 개념 — Expo Go vs Dev Client vs EAS Build](#2-핵심-개념--expo-go-vs-dev-client-vs-eas-build)
3. [무료 한도 확인](#3-무료-한도-확인)
4. [사전 준비 (1회만)](#4-사전-준비-1회만)
5. [Dev Client 빌드 (EAS 클라우드)](#5-dev-client-빌드-eas-클라우드)
6. [팀원에게 공유하기](#6-팀원에게-공유하기)
7. [일상 개발 워크플로](#7-일상-개발-워크플로)
8. [언제 다시 빌드해야 하나](#8-언제-다시-빌드해야-하나)
9. [자주 발생하는 오류](#9-자주-발생하는-오류)

---

## 1. 언제 이 가이드가 필요한가

### Expo Go로 충분한 경우 (지금까지 우리)
- `expo-camera`, `expo-router`, `expo-speech` 같은 **Expo SDK 모듈만** 사용
- 팀원에게 코드 공유 → 각자 Expo Go 앱에서 QR 스캔 → 끝

### Dev Client가 필요한 경우 (이 가이드)
- **비-Expo 네이티브 모듈** 추가 필요 (예: `react-native-vision-camera`)
- 카메라 프리뷰 깜빡임 해결 (Expo Go의 `takePictureAsync` 한계)
- 백그라운드 작업, 푸시 알림 커스텀, BLE 등 Expo SDK에 없는 기능

> **현재 ShadowFit**: 아직 vision-camera 도입 결정 전. 도입 시 이 가이드대로 진행.

---

## 2. 핵심 개념 — Expo Go vs Dev Client vs EAS Build

### 비유로 이해하기

- **Expo Go** = 누군가 만들어 놓은 **범용 리모컨 앱** (스토어에서 받음)
- **Dev Client** = ShadowFit **전용 리모컨** (내가 직접 빌드해서 폰에 설치)
- **EAS Build** = 그 전용 리모컨을 **만들어주는 클라우드 공장**

### 표로 비교

| 항목 | Expo Go | Dev Client |
|---|---|---|
| **앱 이름** | "Expo Go" (고정) | "ShadowFit Dev" (내가 정함) |
| **설치 경로** | Play Store / App Store | 내가 빌드한 APK/IPA |
| **네이티브 모듈** | Expo SDK가 정한 것만 | **내 `package.json`의 모든 모듈** |
| **JS 핫리로드** | ✅ | ✅ (동일) |
| **Metro 서버 필요** | ✅ | ✅ (동일) |

### Metro 서버는 그대로

Dev Client로 바꿔도 `npx expo start`로 Metro 띄우는 방식은 똑같습니다.
**달라지는 건 폰에 깔린 앱뿐**입니다.

---

## 3. 무료 한도 확인

EAS Build의 무료 플랜(2026 기준):

| 항목 | 무료 한도 |
|---|---|
| Android 빌드 | **월 30회** |
| iOS 빌드 | **월 30회** |
| 빌드당 최대 시간 | 60분 |
| 동시 빌드 | 1개 (대기열) |
| EAS Update (OTA) | 월 활성 사용자 1,000명 |
| 다운로드 페이지 | **무제한 / 영구 보관** |

### 캡스톤 규모에서 충분한 이유

- 네이티브 모듈 추가/변경 시에만 빌드 (보통 주 1~3회)
- JS 코드 수정은 Metro 핫리로드로 처리 (빌드 불필요)
- 한 달 30회면 매일 1번씩 빌드해도 남음

---

## 4. 사전 준비 (1회만)

### 4-1. Expo 계정 생성

이미 Expo Go 앱에서 Google 로그인한 적이 있다면:
- https://expo.dev → Google 로그인 → **Settings에서 비밀번호 별도 설정**
- 그래야 터미널에서 `eas login`이 가능합니다.

계정이 없다면 https://expo.dev/signup 에서 가입 (무료).

### 4-2. EAS CLI 설치

```powershell
npm install -g eas-cli
```

설치 확인:
```powershell
eas --version
```

`eas-cli/x.x.x` 같은 버전이 나오면 성공.

### 4-3. EAS 로그인

```powershell
eas login
```

이메일/비밀번호 입력. 로그인 상태 확인:
```powershell
eas whoami
```

---

## 5. Dev Client 빌드 (EAS 클라우드)

### 5-1. expo-dev-client 설치

`frontend/` 폴더에서:
```powershell
npx expo install expo-dev-client
```

이 패키지가 들어가야 빌드된 APK가 **"Dev Client 앱"으로 동작**합니다.
(Metro 서버 연결 화면이 뜨는 등)

### 5-2. EAS 프로젝트 초기화

`frontend/` 폴더에서:
```powershell
eas build:configure
```

질문에 답:
- 플랫폼 선택: **Android** (또는 All)
- `eas.json` 파일이 자동 생성됨

생성된 `eas.json` 기본 구조:
```json
{
  "cli": { "version": ">= 5.0.0" },
  "build": {
    "development": {
      "developmentClient": true,
      "distribution": "internal"
    },
    "preview": {
      "distribution": "internal"
    },
    "production": {}
  }
}
```

### 5-3. 첫 빌드 실행

```powershell
eas build --profile development --platform android
```

진행 흐름:
1. `app.json`에서 패키지 이름 자동 추론 (또는 입력 요청)
2. 코드를 Expo 서버로 업로드
3. **클라우드에서 빌드 시작** (10~20분 소요)
4. 완료 시 터미널에 다운로드 링크 표시
5. 동시에 https://expo.dev/accounts/{내계정}/projects/shadowfit/builds 에서도 확인 가능

> **첫 빌드는 20분 이상 걸릴 수 있음.** 의존성이 많기 때문. 두 번째부터는 빨라집니다.

### 5-4. 폰에 설치

빌드 완료 후:
1. 폰 브라우저로 다운로드 링크 열기
2. APK 다운로드 → 설치
3. "출처를 알 수 없는 앱 허용" 같은 시스템 경고 허용
4. 홈 화면에 **"ShadowFit Dev"** (또는 설정한 이름) 아이콘 생성

> **Expo Go는 지우지 않아도 됩니다.** 둘은 별개 앱이라 공존 가능.

---

## 6. 팀원에게 공유하기

빌드 완료 페이지(expo.dev 대시보드)에서:
1. 빌드 상세 페이지 진입
2. **"Install"** 버튼 옆 링크 복사
3. 팀 카톡/Slack에 링크 공유
4. 팀원이 폰 브라우저로 링크 열기 → APK 받기 → 설치

이 링크는 **영구 보관**되므로, 새 팀원이 와도 같은 링크로 공유 가능.

### 빌드 히스토리

- expo.dev 대시보드에 모든 빌드가 누적
- 옛 버전 다운로드도 가능 (롤백 필요 시 유용)

---

## 7. 일상 개발 워크플로

### Dev Client 설치 이후 매일 하는 일

```powershell
# frontend 폴더에서
npx expo start --dev-client
```

`--dev-client` 옵션 중요. 이게 있어야 ShadowFit Dev 앱과 통신.

이후:
1. 폰에서 **ShadowFit Dev 앱** 실행 (Expo Go 아님)
2. 앱 첫 화면에서 **"Enter URL manually"** 또는 QR 스캔
3. Metro 주소 입력 (예: `exp+frontend://expo-development-client/?url=http://192.168.x.x:8081`)
4. 이후엔 앱 열면 자동으로 마지막 Metro 서버 연결

### JS 코드 수정만 했을 때

- 그냥 저장 → 핫리로드 → 즉시 화면 갱신
- **빌드 불필요** (Expo Go와 동일)

### 네이티브 모듈 추가/수정했을 때

- 새 `eas build` 필요 (5-3 다시 실행)
- 무료 한도(월 30회) 깎임

---

## 8. 언제 다시 빌드해야 하나

| 변경 사항 | 재빌드 필요? |
|---|:---:|
| `app/`, `components/`, `services/` 안의 TSX/TS 파일 수정 | ❌ |
| `assets/` 이미지 추가/교체 | ❌ |
| `package.json`에 **순수 JS 라이브러리** 추가 (axios, zustand 등) | ❌ |
| `package.json`에 **네이티브 모듈** 추가 (`react-native-*`) | ✅ |
| `app.json`의 `plugins`, `android.permissions` 수정 | ✅ |
| `expo` SDK 버전 업그레이드 | ✅ |

> **확신 안 서면 빌드해 보세요.** 어차피 클라우드가 돌리니 내 PC 부담 없음.

---

## 9. 자주 발생하는 오류

### 9-1. `eas login`이 안 됨 (Google 계정 사용자)

증상: 비밀번호 입력했는데 "Invalid credentials".

원인: Expo Go에서 Google 간편가입한 계정은 **비밀번호가 없습니다**.

해결:
1. https://expo.dev 접속 → Google 로그인
2. Settings → Account → **Set password** 클릭
3. 새 비밀번호 설정 후 터미널에서 `eas login` 재시도

### 9-2. 빌드 실패 — "Gradle build failed"

원인: 네이티브 모듈 호환성 충돌, SDK 버전 불일치.

해결:
1. 빌드 로그 확인 (대시보드 → 실패한 빌드 → Logs)
2. 가장 흔한 원인: `expo doctor` 실행해서 의존성 점검
   ```powershell
   npx expo-doctor
   ```
3. 권장 버전으로 정렬:
   ```powershell
   npx expo install --check
   ```

### 9-3. Dev Client 앱이 Metro에 연결 안 됨

증상: ShadowFit Dev 앱 켰는데 "Could not connect to development server".

원인:
- 폰과 PC가 같은 Wi-Fi 아님
- Windows 방화벽이 8081 포트 차단

해결:
```powershell
# 관리자 PowerShell에서
New-NetFirewallRule -DisplayName "Expo Metro" -Direction Inbound -Port 8081 -Protocol TCP -Action Allow
```

또는 터널 모드:
```powershell
npx expo start --dev-client --tunnel
```

### 9-4. 빌드 대기열이 너무 김

원인: 무료 플랜은 대기열 우선순위 낮음 (피크타임 10~30분 대기).

해결:
- 한국 오전(미국 새벽)에 빌드 시도 → 대기 거의 없음
- 급하면 유료 플랜으로 일시 업그레이드 ($19/월) → 작업 끝나면 해지

### 9-5. APK 설치 시 "앱이 설치되지 않음"

원인: 같은 패키지 이름의 옛 APK가 이미 설치되어 있음 (서명 다름).

해결: 폰에서 옛 ShadowFit Dev 앱 삭제 후 재설치.

---

## 빠른 명령어 요약

```powershell
# 최초 1회
npm install -g eas-cli
eas login
npx expo install expo-dev-client
eas build:configure

# 빌드 (네이티브 모듈 변경 시마다)
eas build --profile development --platform android

# 일상 개발
npx expo start --dev-client

# 점검
npx expo-doctor
eas whoami
eas build:list
```

---

## 관련 문서

- [14-how-to-run.md](14-how-to-run.md) — 일반 실행 가이드 (Expo Go 기반)
- [19-deployment.md](19-deployment.md) — 출시 빌드 가이드
- 공식 문서:
  - https://docs.expo.dev/develop/development-builds/introduction/
  - https://docs.expo.dev/build/introduction/
