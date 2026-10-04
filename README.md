# ShadowFit — 통합 레포


> 제품 소개·핵심 기능·아키텍처는 [조직 프로필](https://github.com/Shadowfit)을 참고하세요. 이 문서는 **이 레포를 어떻게 실행하고 개발하는지**만 다룹니다.

---

## DB 운영 실험 기록

> 백엔드·DB 담당 [@Khyojae](https://github.com/Khyojae) · 합성 데이터 1억 행 · 실제 사용자 트래픽 없음

`pose_data` 테이블을 1억 행까지 키워 놓고 스키마 변경·보존·백업·복제를 직접 해 본 기록입니다.

- **한 장 요약** — [`docs/portfolio/one-pager.md`](./docs/portfolio/one-pager.md) (수치마다 조건 표기)
- **스크립트** — [`loadtest/`](./loadtest/) · **결과** — [`loadtest/results/`](./loadtest/results/)

| 실험 | 핵심 수치 | 조건 | 기록 |
| :--- | :--- | :--- | :---: |
| 무중단 스키마 변경 | 쓰기 정지 **69초 → 0.36초** | EC2 · 1,000만 행 | [결과](./loadtest/results/online-ddl-aws-2026-08-12/README.md) |
| 보존 기간 삭제 | 삭제 **18.6분 → 1.8초** | 로컬 · 1억 행 | [결정](./docs/decisions/pose-data-partition-fk-tradeoff.md) |
| 백업·복구 | 복구 **21분**(논리) · **174초**(물리) | EC2 · 1억 행 | [결과](./loadtest/results/backup-restore-aws-2026-08-13/README.md) |
| 복제 | 지연 **약 60초** · 반동기 처리량 **−5.9%** | EC2 2대 · 1,000만 행 | [결과](./loadtest/results/replication-aws-2026-08-22/README.md) |
| 쓰기 처리량 | 초당 **267건 → 1,013건** | EC2 2대 · 36판 | [결과](./loadtest/results/session-spread-aws-2026-08-17/README.md) |
| 적재량 줄이기 | 초당 **222건 → 914건** | EC2 2대 · 8판 | [결과](./loadtest/results/session-spread-aws-2026-08-17/P2-downsample-multisession.md) |

좋아진 것만 적지 않고 그 대가와 원인을 함께 남겼습니다.

- **스키마 변경의 대가** — 작업 시간 1.64배, binlog 441MB 증가 ([결정 문서](./docs/decisions/online-ddl-vs-blocking-alter.md))
- **파티션의 대가** — 외래 키를 쓸 수 없어 회원 탈퇴 시 삭제를 즉시 비동기 처리로 대체
- **복구 시간 정정** — 처음 잰 9초는 파일 시스템이 복사를 건너뛴 값이라 174초로 고쳐 적음 ([교정 기록](./loadtest/results/restore-reflink-2026-08-14/README.md))
- **쓰기 처리량의 원인** — 디스크가 아니라 행 락 대기. 락을 기다린 요청이 30,000건 중 29,999건에서 40건으로 줄어듦

---

## 빠른 시작

```bash
git clone https://github.com/Shadowfit/init.git
cd init
cp .env.example .env   # 값 채우기
docker compose up -d   # mysql + backend + ai-server 전부 기동
```

프론트엔드는 별도로 `frontend/`에서 `npx expo start`. 전체 순서(사전 설치 포함)는 [`docs/14-how-to-run.md`](./docs/14-how-to-run.md) 참고.

---

## 폴더 구조

| 폴더 | 내용 |
| :--- | :--- |
| `frontend/` | React Native(Expo) 앱 |
| `backend/` | Spring Boot API 서버 |
| `ai-server/` | FastAPI 자세 분석 서버 |
| `mysql/` | 초기 스키마·시드(`schema.sql`, `data.sql`) |
| `docs/` | 설계·운영 문서 (API, DB, 아키텍처, 트러블슈팅 등) |
| `loadtest/` | 부하 테스트 스크립트 (ghz, seed 스크립트) |
| `postman/` | API 테스트용 Postman 컬렉션 |

전체 구조는 [`docs/02-folder-structure.md`](./docs/02-folder-structure.md)에 더 자세히 있습니다.

---

## 파트별 안내

### 📱 Frontend — `frontend/`

React Native(Expo) 클라이언트. 화면, 카메라 촬영, TTS 재생을 담당합니다.

```bash
cd frontend
npm install
npx expo start
```

### ⚙️ Backend — `backend/`

Spring Boot API 서버. 회원·인증(JWT)·세션 라이프사이클·리포트·gRPC 연동을 담당합니다.

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'   # dev 프로파일이 Swagger·SQL 로그를 켠다
```

- API 문서: 로컬 기동 후 `http://localhost:8080/swagger-ui` — **dev 프로파일에서만** 열린다. 프로파일 없이 띄우면 안전 기본(문서·SQL 로그 꺼짐, `application.yml` 머리말)
- 설계 문서: [`docs/07-api-design.md`](./docs/07-api-design.md), [`docs/05-database-design.md`](./docs/05-database-design.md)

### 🤖 AI Server — `ai-server/`

FastAPI 서버. MediaPipe로 관절 좌표를 추출하고 DTW로 기준 동작과 비교합니다.

```bash
cd ai-server
pip install -r requirements.txt
uvicorn app.main:app --reload
```

- 가이드: [`docs/06-mediapipe-guide.md`](./docs/06-mediapipe-guide.md)

---

## 서비스 간 연동

Backend ↔ AI Server는 gRPC로 통신합니다. 스키마는 양쪽에 각각 있는 `exercise.proto`이며, 하나를 고치면 **양쪽 다 수동으로 동기화**해야 합니다. 결합 구조 상세는 [`docs/architecture/`](./docs/architecture/README.md) 참고.

```
frontend --(REST)--> backend --(gRPC)--> ai-server
                ^                            |
                └──────── (gRPC callback) ───┘
frontend --(HTTP, 카메라 프레임)--> ai-server
```

---

## 더 읽을 문서

| 문서 | 내용 |
| :--- | :--- |
| [`docs/14-how-to-run.md`](./docs/14-how-to-run.md) | 처음 세팅부터 실행까지 전체 가이드 |
| [`docs/13-docker-setup.md`](./docs/13-docker-setup.md) | Docker Compose 구성 |
| [`docs/07-api-design.md`](./docs/07-api-design.md) | REST API 설계 |
| [`docs/05-database-design.md`](./docs/05-database-design.md) | DB 스키마 |
| [`docs/architecture/`](./docs/architecture/README.md) | AI↔Backend 결합 구조 |
| [`docs/18-testing-guide.md`](./docs/18-testing-guide.md) | 테스트 가이드 |
| [`docs/17-error-codes.md`](./docs/17-error-codes.md) | 에러 코드 |

---

