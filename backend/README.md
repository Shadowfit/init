# ShadowFit Backend

관절 좌표 기반 AI 자세 분석 피트니스 앱 **ShadowFit**의 Spring Boot API 서버입니다.

> 이 레포는 [Shadowfit/init](https://github.com/Shadowfit/init) 모노레포의 `backend/` 모듈이 자동으로 미러링된 것입니다. 프론트엔드·AI 서버를 포함한 전체 구조와 설계 문서는 모노레포 쪽을 참고하세요.

## 기술 스택

- **Spring Boot 3.4 / Java 21**, Spring Security, Spring Data JPA
- **MySQL 8.0**, gRPC (`grpc-server/client-spring-boot-starter`), JWT(jjwt)
- Gradle, Docker

## 실행 방법

### Docker Compose (MySQL + Backend 한 번에)

```bash
cp .env.example .env   # 값 채우기
docker compose up -d
```

### 로컬 실행 (Gradle)

```bash
./gradlew bootRun
```

MySQL은 별도로 띄워야 하며 `.env`의 `DB_HOST=localhost` 설정을 사용합니다.

기동 후 API 문서: `http://localhost:8080/swagger-ui`

## 주요 기능

- gRPC 기반 비동기 실시간 운동 관절 좌표 저장 시스템
- AI 분석 결과를 페르소나별로 분기해 batch gRPC로 통합 전송하는 TTS 피드백 파이프라인
- 세션 데이터 기반 동기화율·구간별 정확도·이전 대비 비교를 제공하는 일별 활동 리포트 API
- JWT 기반 인증/인가

## 기술적 문제 해결

- 부하테스트(ghz/Locust)로 세션 저장 경로 병목을 실측·귀속, JdbcTemplate batch insert 전환·페이지네이션(offset→keyset)·파티셔닝 적용으로 대용량 조회/삭제 성능 개선
- 다중 사용자 세션 갱신 시 발생하는 lost-update를 재현하고, 원자 UPDATE·비관적 락·낙관적 락(CAS) 방지책을 실측 비교해 데이터 정합성 확보

실험 과정과 트레이드오프는 모노레포의 [`docs/decisions/`](https://github.com/Shadowfit/init/tree/main/docs/decisions)에 기록되어 있습니다.
