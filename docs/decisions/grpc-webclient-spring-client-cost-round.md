# 설계: Spring 클라이언트 쪽 호출당 CPU — «WebClient 가 gRPC 스텁보다 비싼가» 를 스레드 단위로 (6차 라운드)

상태: ✅ **실행 완료 (2026-09-14)** — 결과: [`spring-client-cost-aws-2026-09-14`](../../loadtest/results/spring-client-cost-aws-2026-09-14/README.md). **답: §6 의 첫 번째 문장 — WebClient 경로가 호출당 +0.37~0.53 cpu-ms 더 쓴다(블록 5 전부 안 겹침), 자리는 Reactor 이벤트루프(0.74~0.88 vs gRPC netty ELG 0.31). 호출 스레드(Tomcat) 몫은 두 팔이 같다** — «블로킹 브리지» 가설은 CPU 로는 안 보인다. 채택 판단은 안 바뀐다(설계 §1 그대로).
작성: 2026-09-14
배경: 4차·5차 모두 **Spring 컨테이너의 CPU/호출은 판별 불가**였다(4차 §4-1 · 5차 §4-3). 칸이 1~5초라 cgroup 차분에 JIT·GC·아웃박스
발행기·액추에이터 스크레이프가 섞이고, 재부착 핸들러 자체가 호출당 16~45 cpu-ms 라 그 안의 1ms 를 못 가른다. 그래서 지금 문장은
「Spring 쪽 대가는 못 봤다 — 상한은 왕복 델타(c=1 +2.3~3.8ms)에서 AI 쪽(1.5~2.5)·홉(≤0.9)을 뺀 나머지」다. 사용자가 이걸 **실측으로 닫자**고 했다.
연관: [`grpc-webclient-native-rest-round.md`](./grpc-webclient-native-rest-round.md)(5차) · [`grpc-webclient-concurrency-round.md` §2-1](./grpc-webclient-concurrency-round.md)(두 팔의 스레드 구조) ·
[`grpc-webclient-empirical-comparison.md` §11](./grpc-webclient-empirical-comparison.md) · [`load-test-strategy.md` §2-1](./load-test-strategy.md)(겹침 판정)

---

## 1. 질문 하나

*같은 재부착 호출 1건을 보낼 때, Spring 프로세스 안에서 **클라이언트 경로가 쓰는 CPU** 가 gRPC 스텁과 WebClient 사이에 다른가 — 다르면 얼마고, 어느 스레드에서 나는가.*

> 🔴 **이 라운드는 채택 판단을 안 바꾼다** — 상한이 이미 1~2ms 로 잡혀 있다(위 배경). 재는 이유는 «WebClient 자체가 어떠하다» 를 문장으로
> 쓰기 위해서다. 결과가 «판별 불가» 로 끝날 수 있고, 그러면 상한 문장이 그대로 남는다(§6). 채택은 여전히 사용자 몫.

---

## 2. 두 팔의 Spring 쪽 코드 경로 — 어느 스레드가 무엇을 하나

4차 §2-1 을 스레드 이름(5차 구조 게이트, `/proc/*/task/*/comm` 15자 절단)으로 다시 적는다.

| 일 | A. gRPC 블로킹 스텁 | C. WebClient `.block()` | 스레드(comm 접두) |
|---|---|---|---|
| 재부착 핸들러(JWT·DB 에서 기준 좌표 114KB 읽기·응답) | 같음 | 같음 | `http-nio-8080-e`(Tomcat exec) — **두 팔 공통, 호출당 16~45 cpu-ms 의 대부분** |
| 요청 직렬화 | 호출 스레드에서 protobuf 프레이밍 | 🟡 이벤트루프에서 Jackson 인코딩(구독 시점) | A: `http-nio-8080-e` / C: `reactor-http-ep` |
| I/O·응답 수신 | `grpc-default-wo`(netty ELG) | `reactor-http-ep` | 팔마다 다름 |
| 응답 디코딩·호출자 깨우기 | ThreadlessExecutor — **호출 스레드가 스스로** | 이벤트루프가 디코딩, 래치로 호출자 깨움 | A: `http-nio-8080-e` / C: `reactor-http-ep` |
| 콜백·아웃박스·스케줄 | 같음 | 같음 | `grpc-default-ex`·`shadowfit-sched` — 공통, 판정 밖 |
| 배경 | JIT·GC·액추에이터 스크레이프 | 같음 | `C2 CompilerThre`·`C1`·`G1*`·`VM Thread`·`http-nio-9090-e` — **분리해서 뺀다** |

그러므로 «클라이언트 경로 CPU» = **`http-nio-8080-e` + `reactor-http-ep` + `grpc-default-wo`** 의 합이고, 팔 간 차이(C−A)가 곧 클라이언트 대가다.
핸들러 몫은 두 팔에서 같으니 뺄셈에서 사라진다 — 단 **그 몫의 분산이 분해능을 정한다**(§4).

---

## 3. 무엇을 재나

| # | 지표 | 어디서 | 역할 |
|---|---|---|---|
| 1 | **스레드 그룹별 CPU/재부착** — `/proc/1/task/*/schedstat` 의 `sum_exec_runtime`(ns) 을 칸 전후로 읽어 comm 접두로 묶어 차분 ÷ 성공 건수 | 백엔드 컨테이너 | **주 판정.** 그룹: `tomcat`(8080-e) · `reactor`(reactor-http-ep) · `grpc-elg`(grpc-default-wo) · `grpc-exec`(grpc-default-ex) · `actuator`(9090-e) · `jit`(C1/C2) · `gc`(G1/VM Thread) · `sched`(shadowfit-sched) · `other` |
| 2 | 위 셋(tomcat+reactor+grpc-elg)의 합 = **클라이언트 경로 CPU/호출** | 계산 | 주 판정의 한 줄 |
| 3 | 컨테이너 cgroup CPU/호출 | 4·5차와 같음 | 이어 읽기 — 1번의 합과 맞는지(스레드 소멸로 새는 양) |
| 4 | Reattach 왕복 p50/평균 | `shadowfit.ai.call` | 상한 확인 |
| 5 | AI CPU/호출 | AI 컨테이너 | 5차 재현 확인(보조) |

### 3-1. 계기의 함정 — 미리 적는다

- **스크레이프가 tomcat 스레드에 얹힌다.** 4·5차 rig 은 칸 전후에 `/actuator/prometheus` 를 긁고(9090 은 별도 스레드풀이라 안전) **배수 루프가 `stop_count` 로 같은 액추에이터를 5초마다 친다**(9090 — 역시 안전). 8080 을 치는 건 k6 뿐이라 tomcat 그룹은 오염이 없다. 그래도 **스레드 스냅샷은 k6 종료 직후, 배수 전에** 찍는다.
- **스레드가 사라지면 그 CPU 도 사라진다.** Tomcat exec·reactor·ELG 는 장수 스레드라 괜찮고, 짧은 스레드(`task-N`·`parallel-N`)는 `other` 로 묶는다. 3번(cgroup 합)과의 차이가 «샌 양» 이다.
- **JIT 상태가 블록마다 다르다.** 팔 전환 = 재기동이라 매 블록 첫 칸은 컴파일 중이다 — 버림 칸을 둔다(§5).
- **`schedstat` 은 커널 CONFIG_SCHED_INFO 가 켜져 있어야 한다** — AL2023 6.1 은 켜져 있다(5차 박스에서 확인 안 함 → 게이트로).

---

## 4. 분해능 — 왜 이번엔 가를 수 있다고 보나 (그리고 못 가를 수도 있는 이유)

5차 c=1 의 backend CPU/호출은 블록 범위가 **16~45 cpu-ms** 로 세 자릿수 % 만큼 흔들렸다. 원인 셋 — (i) 칸 1.5초에 JIT·GC 가 통째로 섞임, (ii) 표본 100건, (iii) 아웃박스·스케줄 스레드가 같이 잡힘.
이 설계는 (i)(iii) 을 **스레드 분리**로 빼고, (ii) 를 **칸을 길게** 해서 줄인다. 그래도 남는 건 **핸들러 몫(DB 읽기·JSON 직렬화)의 블록 간 산포**인데, 이건 두 팔 공통이라 크기가 같아도 **블록마다 다른 값**이면 뺄셈 범위가 넓어진다.

**정직한 기대**: 클라이언트 대가가 ≥1 cpu-ms 면 갈리고, 0.3 cpu-ms 안팎이면 이번에도 겹칠 수 있다. 그 경우 결론은 「상한 1~2ms → 상한 ≤ 이번 범위 폭」으로 좁아질 뿐이다. 그것도 답이다.

---

## 5. 팔·c·N·절차

| 항목 | 값 | 왜 |
|---|---|---|
| 팔 | **A grpc · C webclient-native** (2팔) | B 는 Spring 쪽이 C 와 동일. D 는 Spring 쪽이 오히려 덜 일한다(`@JsonRawValue`) — §7 ① 에서 넣을지 결정 |
| c | **1** | 질문이 호출당 고정비. 동시성 대가는 4차가 «검출 안 됨» 으로 닫았다 |
| N (VU 당 재부착) | **2,000** → 칸 ≈ 15~20초(c7i, 재부착 ~8ms) | 표본 20배. 칸이 길어져 JIT 정착분이 희석된다. c=1 이라 AI 는 한가하다(포화 밖) |
| 버림 | 팔 전환 뒤 **워밍업 칸 2개**(각 N=500) → 배수 → 본판 | 첫 칸은 컴파일 중 — 5차 게이트가 «ELG 는 게으르게 생긴다» 로 확인한 것과 같은 자리 |
| 블록 | 5, 팔 순서 반전(A→C / C→A) | 2팔이라 반전으로 충분 |
| 드라이버 | 4·5차 rig 그대로(k6 VU=세션, 재부착 반복) — `ARMS="grpc webclient-native" LEVELS="1" N=2000 WARMUP=500` | 프로덕션 클라이언트를 프로덕션 핸들러가 부른다 — 2차 이후 유지한 전제 |
| rig 추가 | ① `thread_snapshot` — `docker exec shadowfit-backend sh -c 'for t in /proc/1/task/*; do echo "$(cat $t/comm) $(cut -d" " -f1 $t/schedstat)"; done'` 를 칸 전후(k6 직후, 배수 전)에 `threads/b{b}_{arm}_c1_{before\|after}.txt` 로 ② 집계기 `analyze_spring_client_cost.py` — 그룹 차분 ÷ `reattach_ok` ③ 게이트 — schedstat 읽힘·그룹별 스레드 수 | rig 이 «칸 전 스냅샷 → k6 → **스냅샷** → 배수» 순서가 되게 `run_cell` 을 한 줄 고친다 |
| 라운드 전용 조건 | 5차와 같음(오버레이 4 + nginx 버퍼) | 조건 차이를 안 늘린다 |
| 시간 | 재기동 10회 × 1.5분 + 칸 10개 × ~20초 + 워밍업 ≈ **25분** | 인스턴스 1대 c7i.2xlarge |

---

## 6. 판정 규칙 (실행 전 고정)

겹침 판정만([`load-test-strategy.md` §2-1](./load-test-strategy.md)).

1. **클라이언트 경로 CPU/호출**(tomcat+reactor+grpc-elg)의 블록 5개 범위가 두 팔에서 안 겹칠 때만 «다르다». 델타 = C − A.
2. 그룹별로도 같은 규칙 — **어디서** 나는지: A 의 tomcat 이 C 의 tomcat 보다 크면 «스텁 프레이밍이 호출 스레드에서», C 의 reactor 가 크면 «인코딩·디코딩이 루프에서».
3. `actuator`·`jit`·`gc`·`sched` 그룹은 판정에 안 넣고 표로만 — 이들이 팔 간에 안 겹치면 «배경이 팔에 따라 다르다» 를 적고 원인 후보를 단다(예: WebClient 팔이 GC 를 더 유발).
4. 실패 칸은 뺀다(4차 규칙 3).

닫는 문장 셋 중 하나:
- 안 겹침, C > A → **«WebClient 경로가 호출당 X~Y cpu-ms 더 쓴다, 자리는 [tomcat|reactor]»**. 상한 문장을 이 값으로 바꾼다.
- 안 겹침, C < A → 같은 형식, 부호 반대. 이 경우 5차 왕복 델타는 전부 AI 쪽+홉이다.
- 겹침 → **«Spring 쪽 대가는 이 분해능(범위 폭 Z)에서도 판별 불가 — 상한은 min(1~2ms, Z)»**. 더 좁히려면 프로세스 안 계기(ThreadMXBean CPU 타이머)가 필요한데 그건 팔에 비대칭으로 붙는다(§8).

---

## 7. 미결 — 착수 전에 사용자 확인이 필요한 것

| # | 분기 | 후보 | 추천 | 왜 |
|---|---|---|---|---|
| ① | 팔 | (a) **A·C 둘** / (b) A·C·D 셋 | **(a)** | D 는 Spring 이 이스케이프를 안 해 «REST 답게 가면 Spring 쪽은 싸진다» 를 볼 수 있지만 5차가 AI 쪽에서 D 를 이미 기각했다. 질문을 하나로 |
| ② | 드라이버 자리 | (a) **유저 저니 그대로**(k6 → 재부착 컨트롤러 → 클라이언트) / (b) 측정 전용 벤치 엔드포인트(핸들러 없이 클라이언트만 N회 루프) | **(a)** | (b) 는 핸들러 몫(16~45)을 통째로 빼서 분해능이 자릿수로 좋아지지만 **Spring 본 코드에 측정용 엔드포인트**가 들어간다(AI 미러와 같은 성격). (a) 가 겹치면 그때 (b) |
| ③ | N | (a) **2,000** / (b) 5,000 | **(a)** | 칸 15~20초면 JIT 정착분이 <10%. (b) 는 라운드 2배, 얻는 건 분산 √2.5 |
| ④ | 워밍업 | (a) **칸 2개(N=500)** / (b) 4차 그대로(WARMUP=10) | **(a)** | 10건으로는 C2 가 안 돈다 |
| ⑤ | 계기 | (a) **`/proc/1/task/*/schedstat`**(커널, JVM 무관) / (b) JFR 녹화 | **(a)** | (b) 는 JVM 플래그·파일 회수·파서가 더 붙고 오버헤드가 팔에 같이 걸리긴 해도 크다 |
| ⑥ | calib | (a) **넣는다** / (b) 안 넣는다 | **(a)** | ㉠ — 5차와 같이 phase 시작·끝 |

---

✅ **2026-09-14 사용자 결정: ①~⑥ 전부 (a).**

## 8. 안 재는 것

- **프로세스 안 계기**(ThreadMXBean·Micrometer 로 호출 구간 CPU) — A 는 호출 스레드에서 다 하고 C 는 루프에서 하므로 같은 계기가 두 팔에서 다른 것을 잰다. 비대칭.
- **동시성** — 4차.
- **AI 쪽 내부 분해** — 5차 §6.
- **채택.**

## 9. 착수 — rig (2026-09-14 구현·실행)

`measure_ai_call_concurrency.sh` 에 `THREADS=1`(칸 전후 `/proc/1/task/*/schedstat` 스냅샷 — after 는 k6 직후·배수 전)·`WARMUP_CELLS`·게이트(PID 1=java·빈 값 0·그룹 스레드 수)를 더하고,
`analyze_spring_client_cost.py`(그룹 차분 ÷ `reattach_ok`, C−A 겹침)·`run_all.sh` phase `springclient`(calib 시작·끝)를 붙였다. 로컬 스모크(2팔×2블록) 뒤 EC2 —
`RUN_ID=springclient-20260914-075216`, 본 측정 819초, 칸 10개 전부 유효. 결과·서사는 [README](../../loadtest/results/spring-client-cost-aws-2026-09-14/README.md).

**분해능이 예상보다 훨씬 좋았다** — 그룹당 블록 범위 폭 0.05~0.14 cpu-ms(5차 cgroup 의 1/200). §4 의 «0.3 안팎이면 겹칠 수 있다» 는 기우였다. 대신 워밍업 2×500 으로는 C2 가 안 끝나 JIT 가 호출당 3 cpu-ms 로 남았다(판정 그룹 밖).

## 10. 결정 로그

- 2026-09-14: 사용자 「(Spring 쪽 대가는) 실측해야 하니 — 측정해보자」. 초안. §7 ①~⑥ 확정 대기.
- 2026-09-14: **§7 ①~⑥ 전부 (a) 로 사용자 확정** — 유저 저니 드라이버 · 팔 A·C · N=2,000 · 워밍업 칸 2 · schedstat · calib.
- 2026-09-14: **실행 완료.** 첫 번째 문장 — +0.37~0.53 cpu-ms/호출, Reactor 루프. 왕복 델타(+1.64~1.88ms)의 분해가 세 라운드 조각(Spring 0.4~0.5 · AI ~1 · 홉 ≤0.9)으로 닫혔다. [README](../../loadtest/results/spring-client-cost-aws-2026-09-14/README.md) §6.
