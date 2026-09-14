# 설계: 네이티브 REST 팔 — 4차의 «겹의 대가» 에서 겹을 빼면 무엇이 남나 (5차 라운드)

상태: 📝 **설계 초안 — §7 미결 확정 전. 착수 안 함.**
작성: 2026-09-14
배경: 4차([`grpc-webclient-concurrency-round.md`](./grpc-webclient-concurrency-round.md) · [결과 §4-2](../../loadtest/results/ai-call-concurrency-aws-2026-09-11/README.md))가
webclient 팔의 대가를 **AI 쪽 호출당 +1~3 cpu-ms → 포화 처리량 −15%** 로 좁혔는데, 그 REST 미러는
«JSON 파싱 → pydantic → **proto 재조립** → **같은 gRPC 서비서**» 라 gRPC 경로를 감싼 겹이다(`internal_analysis.py:87-106`).
즉 4차의 B 팔은 A 팔이 하는 일을 전부 하고 그 앞에 일을 더 한다 — 이 방향의 차이는 구조적으로 예정돼 있었다.
연관: [`grpc-webclient-empirical-comparison.md` §11](./grpc-webclient-empirical-comparison.md#11-정리--세-라운드가-답한-것과-남은-것-2026-09-10)(현재 상태 한 장) ·
[`load-test-glossary.md`](./load-test-glossary.md)(팔·판·라틴 방격) · [`load-test-strategy.md` §2-1](./load-test-strategy.md)(겹침 판정) ·
[`round-to-round-nonreproducibility.md`](./round-to-round-nonreproducibility.md)(축 B: 같은 박스 stop→start 만으로 +21% — 라운드 간 비교 불가, 결과는 main 의 `loadtest/results/nonrepro-axisB-2026-09-14/`)

---

## 1. 왜 재나 — 4차의 숫자는 «REST» 의 대가가 아니라 «미러 구조» 의 대가다

4차가 채택 판단에 올려놓은 숫자 세 개(c=1 델타 1.2~3.9ms · 포화 처리량 −15% · AI 호출당 +1~3 cpu-ms)는
전부 **지금 만들어 둔 미러 코드**의 값이다. 그 미러는 빨리 만들려고 기존 gRPC 서비서를 재활용하고 앞에
REST 입구만 붙인 것이라, 「REST 로 정리하면 이만큼 손해」 라는 문장의 근거로는 한쪽으로 치우쳐 있다.
REST 로 실제로 정리한다면 그 겹은 없어진다.

그러면 무엇이 남나 — 4차 결과 §8 이 못 가른다고 적은 것이 그대로 남는다:

| 조각 | 4차에서 잰 크기 | 네이티브 REST 로 짜면 |
|---|---|---|
| nginx 홉 | 0.01~0.88ms 지연 · 0.50~0.73 cpu-ms/호출 | **남는다** — nginx 라우팅 재사용이 REST 로 가는 이유라 뺄 수 없다 |
| 114KB 본문 디스크 버퍼링(#730) | 미측정 | nginx 설정으로 없앨 수 있다 — 두 팔 모두에 |
| AI 쪽 겹 = JSON 파싱 + pydantic 검증 + **proto 재조립 + 서비서 호출** | 합쳐서 1~3 cpu-ms | 굵은 글씨는 사라지고 앞 둘은 남는다. **둘의 비율은 한 번도 안 쟀다** |
| Spring 쪽(WebClient ↔ gRPC 스텁) | 판별 불가 | 변화 없음 |

**이 라운드가 답하려는 질문 하나**: *AI 쪽 호출당 1~3 cpu-ms 가운데 얼마가 «겹»(미러가 gRPC 를 감싼 대가)이고
얼마가 «REST 라서 내는 값»(JSON 파싱·pydantic·스레드풀)인가.* 부산물로 c=1 지연 델타와 포화 처리량이
네이티브 팔에서 어디로 가는지도 같은 판에서 나온다.

> 🔴 §11-4 는 ㄷ(내부 분해)을 «채택 판단을 안 바꾼다» 로 뒤로 미뤘다. 이 라운드는 ㄷ 와 다르다 — 잔여의
> Spring 쪽 분해가 아니라 **채택 판단의 입력값(REST 쪽 대가의 크기) 자체**를 고쳐 잰다. 다만 결과가
> 어느 쪽이든 **이 라운드도 채택을 결정하지 않는다.** 수치를 만들고 결정은 사용자가 한다.

> ⚠️ **왜 «결정 뒤 확인» 이 아니라 지금 재나.** 네이티브 REST 팔을 만드는 일이 곧 «REST 로 정리» 작업의
> 절반이라 순서를 뒤집는 게 맞다는 논의가 있었다(2026-09-14). 그럼에도 사용자가 재기로 했다 — §2-2 의
> 구현 방식 (i) 로 서비서를 안 뜯고 팔을 세울 수 있어 «절반» 이 아니라 수십 줄이면 되기 때문이다.

---

## 2. 팔 — 4차의 둘에 네이티브 둘을 더한다

### 2-1. 팔 4개

| 팔 | Spring 클라이언트 | 전선 위 계약 | AI 쪽 경로 | 왜 필요한가 |
|---|---|---|---|---|
| **A. grpc** | `GrpcAiAnalysisClient`(직결, 풀 3) | protobuf | gRPC 서비서 | 기준 |
| **B. mirror** | `WebClientAiAnalysisClient` | JSON, `joint_coordinates` 는 **JSON 문자열을 문자열로 한 번 더 감싼 것** | JSON → pydantic → **proto 재조립 → 서비서** | 4차의 B. **같은 라운드 안에** 있어야 A·C·D 와 비교된다 — 라운드 간 비교는 stop→start 만으로 +21% 흔들려 못 쓴다([`round-to-round-nonreproducibility.md`](./round-to-round-nonreproducibility.md) 축 B) |
| **C. native** | 같은 클래스, 경로만 `/native/…` | B 와 **같은 계약**(문자열 안의 문자열) | JSON → pydantic → **바로 로직**(proto 재조립·서비서 호출 없음) | B−C = «겹» 의 대가. 계약을 안 바꿔서 이것 하나만 가른다 |
| **D. native-nested** | 같은 클래스, DTO 의 `jointCoordinates` 를 `@JsonRawValue` | JSON, `joint_coordinates` 가 **중첩 JSON 배열 그대로** | JSON → pydantic(`list[dict]`) → 로직, **두 번째 파싱 없음** | C−D = «문자열 안의 문자열» 이중 인코딩의 대가. REST 로 정리한다면 계약은 이렇게 짤 것이므로 «진짜 REST 라면» 의 값은 D 다 |

- 4차와 달리 팔이 넷이라 팔 순서는 반전이 아니라 **블록마다 한 칸씩 회전**(§5-2).
- B 는 4차 코드 그대로 — 손대지 않는다. A 도 그대로.
- C·D 는 **Spring 쪽 클래스를 새로 안 만든다** — `WebClientAiAnalysisClient` 에 계약 스위치 하나
  (`ai.webclient.contract: mirror | native | nested`)를 더해 경로와 DTO 만 바꾼다. 전송 계층(WebClient·풀·타임아웃·인코더)이
  세 REST 팔에서 **완전히 같아야** B−C·C−D 뺄셈이 성립한다.

### 2-2. C·D 의 AI 쪽 구현 — 두 방식, 이 라운드는 (i)

| 방식 | 무엇을 하나 | 면적 | 재는 것이 같은가 |
|---|---|---|---|
| **(i) 서비서 그대로, 요청 객체만 pydantic 을 직접 넘김** | `_servicer.ReattachAnalysis(command, ctx)` — 서비서는 `request.session_id` 처럼 **속성만 읽고** `_parse_reference_poses` 는 `ref.joint_coordinates` 문자열에 `orjson.loads` 를 건다. pydantic 모델이 같은 속성명을 갖고 있어 proto 조립 없이 그대로 들어간다. 응답 proto(`ReattachResponse`, 필드 4개) 조립만 남는다 | 라우터 파일 안 **~30줄**(C 라우트 3개), D 는 `_parse_reference_poses` 가 이미 디코드된 `list` 도 받게 **2줄** | ✅ 같다 — (ii) 로 뽑아낸 로직 함수도 결국 pydantic 객체에서 같은 속성을 읽고 같은 `orjson.loads` 를 한다. 응답 proto 조립(필드 4개)이 (i) 에만 남는데, 요청 쪽 1,221개 랜드마크 조립을 뺀 것에 비하면 자릿수가 다르다 |
| (ii) 서비서 로직을 전송 무관 함수로 추출 | `app/core/analysis_commands.py` 에 start/reattach/stop 을 뽑고 gRPC 서비서·REST 라우터가 둘 다 그걸 부른다 | 서비서 **~300줄 리팩터**, 테스트 동반 | ✅ 같다 — 다만 이건 «REST 로 정리» 작업 그 자체다. 채택도 안 했는데 프로덕션 구조를 먼저 고치는 셈 |

**이 라운드는 (i).** (i) 는 측정용이라 `internal_analysis.py` 안에 가두고 그렇게 적는다 — 채택 뒤 (ii) 로
가면 (i) 는 통째로 지워진다. (ii) 를 먼저 하면 «재보고 결정» 이 아니라 «결정한 것처럼 만들고 재기» 가 된다.

⚠️ (i) 의 함정 하나 — 서비서는 `request.persona or "BEGINNER"`·`request.session_nonce or None` 처럼 proto3 의
빈 문자열 기본값에 기대고 있다. pydantic 모델의 기본값도 `""` 라(`internal_analysis.py` 모델) 같은 동작이다.
게이트에서 C·D 의 스모크 응답(`success`·`rep_count`·`already_active`)이 B 와 같은지 **한 건씩 눈으로** 확인한다(§5-4).

### 2-3. D 의 계약 — 무엇이 바뀌고 무엇이 위험한가

- Spring: `ReattachCommand.poses[].jointCoordinates` 에 `@JsonRawValue` — DB 의 `pose_data.joint_coordinates`
  문자열을 **파싱 없이 그대로** JSON 본문에 박는다. Spring 쪽 CPU 는 오히려 준다(이스케이프 안 함).
- AI: `PoseRefDto.joint_coordinates: str | list` 가 아니라 **D 전용 DTO** `PoseRefNestedDto(joint_coordinates: list[dict])`.
  `list[LandmarkDto]` 로 안 하는 이유 — 랜드마크 1,221개를 pydantic 이 개별 검증하면 gRPC 팔이 안 내는 검증
  비용을 D 에만 얹는다. proto 는 그 문자열을 검증 안 하고 복사만 하므로 대칭이 되려면 `dict` 까지만.
- 전선 크기: B·C 는 따옴표마다 `\"` 라 D 보다 크다. 게이트에서 세 팔의 `Content-Length` 를 nginx 액세스 로그로
  적는다 — 숫자는 실측으로만(§5-4).
- 🔴 **프로덕션에 쓴다면** DB 문자열이 유효 JSON 이라는 보장이 필요하다 — 하나라도 깨져 있으면 본문 전체가 422 다.
  rig 페이로드는 고정 템플릿이라 이 라운드엔 안 걸리지만, 채택 시 조건으로 적는다.

---

## 3. 무엇을 재나 — (팔, c) 한 칸마다

4차 §3 의 다섯 지표에서 Spring CPU 를 빼고(4차가 판별 불가로 닫았고 이 라운드의 질문이 아니다) 둘을 더한다.

| # | 지표 | 어디서 | 이 라운드에서의 역할 |
|---|---|---|---|
| 1 | Reattach 왕복 p50/p95 (`shadowfit.ai.call`) | Spring 마이크로미터 | c=1 델타. **주 판정 아님** — 4차에서 지연은 포화 구간에서 큐잉 지배라 판별력이 준다 |
| 2 | 처리량 (`reattach_ok` ÷ 벽시계) | k6 summary | 포화 구간(c≥8)에서 팔별 천장. 4차 −15% 가 C·D 에서 어디로 가나 |
| 3 | **AI CPU/호출** (cgroup 차분 ÷ 성공 건수) | ai 컨테이너 `cpu.stat` | **주 판정.** 4차에서 5수준 전부 안 겹친 지표라 판별력이 있고, 이 라운드의 질문(«겹» 의 몫)에 직접 답한다 |
| 4 | nginx CPU/호출 | nginx 컨테이너 | B·C·D 에서 같아야 한다 — 다르면 계약(D 의 본문 크기)이 홉에 미친 영향 |
| 5 | Start 콜백 지연 | Spring | 보조, 4차와 같음 |
| 6 | **컨테이너 재시작 계수** (`docker events` + `RestartCount`) | 호스트 | #731 의 rig 구멍. 재시작 칸은 규칙 3 으로 뺀다 — 이번엔 **원인을 볼 수 있게** OOM 이벤트·`dmesg` 도 걷는다 |
| 7 | **본문 크기** (`Content-Length`, nginx 액세스 로그) | nginx | 팔별 1회. D 의 «작다» 를 숫자로 |

### 3-1. 잴 수 없는 것 — 미리 적는다

- **JSON 파싱 ↔ pydantic ↔ 스레드풀 전환의 내부 비율.** C 가 남기는 잔여가 이 셋의 합이라는 것까지만. 더 가르려면
  파이썬 프로세스 안 계기(cProfile·`time.perf_counter` 구간)가 필요하고, 그건 계기 자체가 팔에 비대칭으로 붙는다.
- **Spring 쪽 CPU/호출.** 4차 §4-1 과 같은 이유(칸 1~14초, JIT 에 묻힘). C·D 가 Spring 쪽 코드를 안 바꾸므로 이 라운드는 그것을 물을 이유도 없다.
- **개방 루프.** 4차 §8 과 같다.

---

## 4. c 수준 — 4차의 다섯에서 줄인다

4차가 밝힌 것: AI CPU/호출의 기울기는 **c 와 무관**(호출당 고정 비용), 처리량은 **c=8 부터 포화**, c=32 는 재시작 1칸.
이 라운드의 질문은 «호출당 고정 비용의 분해» 라 c 축을 다시 다 훑을 이유가 없다.

| 후보 | c | 칸 수(팔 4 × c × 블록 5) | 무엇을 얻고 잃나 |
|---|---|---|---|
| (a) | **{1, 8}** | 40 | c=1(델타·CPU/호출 기준값) + c=8(포화 처리량). 4차가 답한 «c 무관» 을 전제로 쓴다 |
| (b) | {1, 8, 16} | 60 | (a) + 포화 플래토 확인 1점. 4차에서 16 은 8 과 처리량이 겹쳤다 |
| (c) | {1, 4, 8, 16, 32} | 100 | 4차와 같은 축. 팔이 4개라 시간 2배. c=32 는 #731 재시작 위험 |

시간(§5-2 산식): (a) ≈ 1.5시간 · (b) ≈ 2시간 · (c) ≈ 3.5시간.

---

## 5. 절차

### 5-1. 드라이버 — 4차 rig 그대로

`measure_ai_call_concurrency.sh` + `k6/ab_internal_analysis.js` 를 그대로 쓴다. `ARMS="grpc webclient webclient-native webclient-nested"`
로 팔 4개를 받고, 팔 전환 시 `AI_CLIENT_TYPE`(grpc/webclient) 과 `AI_WEBCLIENT_CONTRACT`(mirror/native/nested) 둘을 싣는다.
VU=세션, 재부착 반복, 계정 교대 — 전부 4차 §5-1.

### 5-2. 블록·순서 — 팔 4개의 회전

팔 전환 = 백엔드 재기동(~1.5분). 블록 = (팔 하나, c 수준 전부). 팔 순서는 블록마다 **한 칸 회전**, c 순서는 칸마다 회전:

```
버림:  A B C D (c 수준 전부 × WARMUP)              ← 기록 안 함
블록1: A B C D
블록2: B C D A
블록3: C D A B
블록4: D A B C
블록5: A B C D        ← 블록1 과 같은 순서. 4팔 회전은 4블록에서 한 바퀴라 5번째는 첫 자리 반복
```

블록 5 가 블록 1 과 같은 순서라 «순서 효과» 는 4블록 안에서 분리되고, 5번째 블록은 표본만 더한다.
블록을 4 로 줄이면 겹침 판정의 범위가 표본 4개가 된다 — 4차·2차와 표본 수를 맞추려고 5 를 유지한다.

- 팔 전환 직후 워밍업(c=1 × WARMUP) → 배수 → 본판. 칸 경계 배수. 전부 4차 §5-2.
- **예상 소요** (a) 기준: 재기동 20회 × 1.5분 + 칸 40개 × ~1.2분 + 배수 ≈ **1.5시간 안팎.**

### 5-3. 공정성 장치 — 4차 §5-3 ①~⑦ 그대로 + 둘

4차의 일곱(`GRPC_MAX_WORKERS=40` · 풀 3 · 아웃박스 200 · AI 메모리 6000m · JWT 14400s · 팔 전환 게이트 · 커밋 SHA 고정)은
그대로. c 최대가 8 이면 AI 메모리는 워커당 풀 ≥3 이면 되지만 **4차와 같은 6000m 을 유지**한다 — 바꾸면 4차와의
조건 차이가 하나 늘 뿐 얻는 게 없다.

8. **nginx 본문 버퍼** — `client_body_buffer_size` 를 본문(114KB)보다 크게 잡아 #730 의 디스크 임시파일을 없앤다.
   세 REST 팔에 **똑같이** 걸리므로 B−C·C−D 뺄셈은 안 더럽히지만, **4차의 B 와 이 라운드의 B 는 이 점에서 다르다** —
   조건으로 적는다. 값은 게이트가 액세스 로그의 임시파일 경고(`a client request body is buffered to a temporary file`)
   **0건**으로 확인한다. → §7 ③
9. **세 REST 팔의 전송 계층 동일성 게이트** — 백엔드 컨테이너에서 `AI_CLIENT_TYPE`·`AI_WEBCLIENT_CONTRACT` 를 `printenv` 로,
   그리고 스모크 1건의 nginx 액세스 로그 경로(`/internal/analysis/reattach` ↔ `/native/reattach` ↔ `/native-nested/reattach`)로 확인.

### 5-4. 구조 확인 게이트 — 버림 블록 직후 한 번

| 확인 | 방법 | 기대 |
|---|---|---|
| 4차 게이트 5개 | 4차 §5-4 그대로 | 4차와 같은 값 |
| C·D 의 응답이 B 와 같은가 | 같은 세션에 B→C→D 재부착 1건씩, `success`·`rep_count`·`already_active` 비교 | 세 팔 동일 (`already_active=true`, 같은 `rep_count`) |
| 세 팔의 `Content-Length` | nginx 액세스 로그 `ab` 포맷 | B = C > D (크기는 실측으로 적는다) |
| 임시파일 버퍼링 | nginx 에러 로그 grep | 0건 |
| 팔별 AI 로그 경로 | ai 컨테이너 로그에서 `ReattachAnalysis 수신` 한 줄 | 네 팔 모두 같은 로그(서비서를 공유하므로) — D 만 `_parse_reference_poses` 의 디코드 생략 |

---

## 6. 판정 규칙 (실행 전 고정)

[`load-test-strategy.md` §2-1](./load-test-strategy.md) 의 **겹침 판정**만. 배수·판정선 없음([[feedback_no_arbitrary_threshold_values]]).

1. **주 판정 — AI CPU/호출, c 별로**: 블록 5개의 범위가 안 겹칠 때만 «두 팔이 다르다». 세 뺄셈을 각각 본다:
   - **B − C** = 겹(proto 재조립 + 서비서 호출)의 대가
   - **C − A** = REST 라서 내는 값(JSON 파싱 + pydantic + 스레드풀), 계약 그대로일 때
   - **C − D** = 이중 인코딩의 대가
2. **처리량(c=8)**: 같은 규칙. 4차의 −15% 가 C·D 에서 «A 와 겹침 / B 와 겹침 / 둘 사이» 어디인지.
3. **c=1 지연 델타**: 같은 규칙, 보조.
4. **재시작·실패 칸**은 규칙 1~3 에서 빼고 따로 적는다(4차 규칙 3).

결론은 다음 넷 중 하나로만 닫는다 — 승패가 아니라 **분해**다:

- C 가 A 와 겹치고 B 와 안 겹침 → **1~3 cpu-ms 는 전부 겹이었다.** REST 의 AI 쪽 대가는 이 무대에서 검출 안 됨.
  남는 REST 대가는 nginx 홉뿐.
- C 가 A·B 둘 다와 안 겹침 → **일부는 겹, 일부는 REST 고유.** 크기를 둘로 나눠 적는다.
- C 가 B 와 겹침 → **겹은 대가가 아니었다.** 1~3 cpu-ms 는 JSON 파싱·pydantic·스레드풀 쪽이고, 4차 §4-2 의
  «구조적으로 예정» 이라는 해석은 **틀렸다** — 그대로 정정한다.
- D 가 C 와 안 겹침 → 위 어느 경우든 **이중 인코딩 몫**을 따로 적는다. 겹치면 «계약 모양은 이 무대에서 무관».

**이 라운드가 답할 수 없는 것**: C 의 잔여 내부(§3-1) · 절대값 · 개방 루프 · **채택.**

---

## 7. 미결 — 착수 전에 사용자 확인이 필요한 것

| # | 분기 | 후보 | 추천 | 왜 |
|---|---|---|---|---|
| ① | 팔 구성 | (a) **A·B·C·D** / (b) A·B·C (D 생략) / (c) A·C·D (B 생략) | **(a)** | (b) 는 «진짜 REST 라면» 의 값이 없다. (c) 는 4차 B 와 라운드 간 비교를 해야 하는데 그게 안 된다(nonrepro 축 B) |
| ② | c 수준 | (a) **{1, 8}** / (b) {1, 8, 16} / (c) 4차 그대로 5수준 | **(a)** | §4. 4차가 «CPU/호출은 c 무관·c=8 포화» 를 이미 답했다 |
| ③ | nginx 본문 버퍼 | (a) **라운드 동안 메모리 버퍼로**(#730 제거) / (b) 4차 조건 그대로(디스크) | **(a)** | 프로덕션이라면 고칠 값이고 세 REST 팔에 같이 걸린다. 다만 4차 B 와의 조건 차이로 기록 |
| ④ | C·D 의 AI 구현 | (a) **(i) pydantic 직접 전달, 라우터 안 ~30줄** / (b) (ii) 서비서 로직 추출 | **(a)** | §2-2. (b) 는 채택 전에 프로덕션 구조를 고치는 것 |
| ⑤ | D 의 랜드마크 검증 | (a) **`list[dict]`(파싱만)** / (b) `list[LandmarkDto]`(필드 검증) | **(a)** | proto 가 안 하는 검증을 D 에만 얹으면 대칭이 깨진다. (b) 가 프로덕션 모양이긴 하나 그건 채택 뒤 |
| ⑥ | 블록 수 | (a) **5**(4차·2차와 같음) / (b) 4(회전 한 바퀴) | **(a)** | 겹침 판정의 표본 수를 앞 라운드와 맞춘다 |
| ⑦ | 인스턴스 | (a) **c7i.2xlarge 1대, 4차와 같은 형상** / (b) 부하기 분리 | **(a)** | 4차 ④ 와 같은 이유 |

---

## 8. 안 재는 것

- **Spring 쪽 CPU/호출** — 4차와 같은 이유로 판별 불가이고, C·D 가 Spring 전송 계층을 안 바꾼다.
- **C 의 잔여 내부 분해**(JSON ↔ pydantic ↔ 스레드풀) — 프로세스 안 계기가 필요하다. 이 라운드 결과가 «REST 고유
  몫이 있다» 로 나오면 그때 6차 후보로.
- **콜백 3개(AI→Spring)** — 미러가 없다.
- **Start·Stop 의 판정** — Start 는 fire-and-forget, Stop 은 아웃박스. 4차 §3-3.
- **채택.**

---

## 9. 착수 — (§7 확정 뒤 채운다)

## 10. 결정 로그

- 2026-09-14: 사용자가 「REST 전용으로 다시 짜서 재면 어떻게 될까」 → 「설계하고 측정하자」. 이 문서 초안.
  §7 ①~⑦ 은 사용자 확정 대기.
