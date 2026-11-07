# 반동기 미복귀 원인 분리 (2026-11-07, 로컬)

설계: [`../../../docs/decisions/replication-lag-and-semisync.md`](../../../docs/decisions/replication-lag-and-semisync.md) §0-2 Q4
앞 라운드: [`../replication-2026-08-17/`](../replication-2026-08-17/) — `_out/Q4_semisync_degrade.txt`
무대: 로컬 Docker · `mysql:8.0` = **8.0.46** 양쪽 · 소스 1 + 리플리카 1 · 포지션 기반(`gtid_mode=OFF`) ·
`wait_point=AFTER_SYNC` · `timeout=10000ms` · `wait_for_replica_count=1`

## 0. 한 줄

**08-17 의 «리플리카가 돌아와도 반동기로 안 돌아온다» 는 MySQL 의 성질이 아니라 복구 절차 탓이었다.**
그런데 원인이 하나가 아니었다 — 리플리카 쪽 설정을 `SET GLOBAL` 로 켜면 재기동에 사라지고,
`SET PERSIST` 로 살려둬도 **재기동 때 자동으로 붙는 수신 스레드가 반동기 없이 붙는다.**
설정을 **시작 옵션**(`--plugin-load-add` + `--loose-rpl-semi-sync-replica-enabled=ON`)으로 주면
추가 조작 없이 1초 안에 돌아왔다.

## 1. 질문

08-17 Q4 의 단서 — «자동 미복구가 rig 의 복구 절차(리플리카 재기동 후 `START REPLICA` 만) 탓인지
갈라내지 않았다» — 를 닫는다. 이진 사실이라 로컬 무대로 충분하다(처리량·지연은 안 잰다).

## 2. 팔과 판

| 팔 | 리플리카 반동기를 켜는 방법 | 재기동 뒤 복구 조작 |
|---|---|---|
| A | `SET GLOBAL` (08-17 rig 과 같음 — `replication_probe.sh:276`) | `START REPLICA` 만 (08-17 과 같음) |
| B | `SET GLOBAL` | `START REPLICA` → `SET GLOBAL ..._enabled=1` → IO 스레드 재시작 |
| C | `SET PERSIST` | `START REPLICA` 만 |
| D | 시작 옵션 (`docker-compose.armD.yml`) | `START REPLICA` 만 |

- A·B·C: 버림 1(A) + 본판 9, 라틴 방격(ABC·BCA·CAB) — 판 순서와 팔을 가른다. 판마다
  `RESET PERSIST` 로 앞 판의 영속 설정이 새지 않게 했다
- D: 리플리카 시작 옵션이 달라 **같은 무대에서 섞을 수 없어 따로** 돌았다 — 버림 1 + 본판 3.
  🔴 그래서 D 는 A·B·C 와 판 순서가 균형 잡혀 있지 않다(결과가 4/4 로 갈려 판정엔 영향 없다고 본다)
- 판마다: 리플리카 `docker stop` → 소스에 3행 → `docker start` → **복구 조작 전에** 리플리카
  `@@rpl_semi_sync_replica_enabled` 를 찍음(원인 증거) → 팔별 복구 → 최대 20초 쓰며 소스 `status` 관찰
- 판정 «복귀» = 소스 `status=ON` · `clients=1` · 복구 뒤 쓰기가 `yes_tx` 로 셈

## 3. 결과

| 팔 | 판 | 재기동 직후 리플리카 `enabled` | 결과 |
|---|---:|---|---|
| A | 본판 3 + 버림 1 | **0** | **본판 3/3 미복귀** (버림 포함 4/4, `OFF`/0) — 08-17 재현 |
| B | 본판 3 | 0 → 수동으로 1 | **3/3 복귀**, 1초 |
| C | 본판 3 | **1** | **3/3 미복귀** (`OFF`/0) ← 예상 밖 |
| D | 본판 3 + 버림 1 | **1** | **본판 3/3 복귀** (버림 포함 4/4), 1초 |

원본: `_out/trials.tsv`(A·B·C) · `_out_armD/trials.tsv`(D) · `_out*/D0_stage.txt`(무대)

공통 관측(전체 관측 14판 = 본판 12 + 버림 2): 리플리카를 멈춘 뒤 **첫 쓰기 10,510~13,281ms** — 설정 timeout 10,000ms 를
그대로 문다(08-17 의 10,658ms 와 같은 결). 그 뒤 `status=OFF`, 에러 없이 통과.

### 3-1. C 진단 — 설정은 켜져 있는데 왜 안 돌아오나

C 한 판을 컨테이너를 남긴 채(`KEEP=1`) 돌려 확인했다 — `_diag/C_diagnosis.txt`.

- 재기동 뒤 리플리카 `@@rpl_semi_sync_replica_enabled=1` 인데 **`Rpl_semi_sync_replica_status=OFF`**
- 리플리카 로그: 수신 스레드가 재기동 시 **자동으로** 소스에 붙었다(12:20:12.553) —
  `ready for connections`(12:20:12.604)보다 먼저
- 설정은 건드리지 않고 **IO 스레드만 재시작** → 2초 뒤 소스 `ON`/1, 리플리카 `status=ON`

⚠️ **추론(검증 안 함)**: 반동기 역할은 수신 스레드가 소스에 붙는 순간 정해지는데, C 는 그 순간에
영속 설정이 아직 반동기 쪽에 반영되지 않았던 것으로 보인다. D 는 플러그인과 설정을 시작 옵션으로
서버 기동 초기에 올려서 그 순서 문제가 없는 것으로 읽는다. 기동 순서를 직접 계측하진 않았다.

### 3-2. D 의 부산물 — `loose-` 가 없으면 기동을 거부한다

1회차(`run_armD_fail1.log`)는 리플리카가 안 떴다. `mysql` 이미지의 첫 기동(`--initialize`)은
`--plugin-load-add` 를 무시하는데, 그 상태에서 플러그인 변수를 만나 `unknown variable
'rpl-semi-sync-replica-enabled=ON'` 으로 중단했다. 플러그인 변수는 `loose-` 접두사로 줘야 한다.

## 4. 조건 — 인용할 때 떼면 안 되는 것

1. **로컬 · 같은 호스트 · 8.0.46** 의 이진 사실이다. 시간 값(1초·2초)은 폴링 간격이라 인용하지 않는다
2. 리플리카 **1대** · `wait_for_replica_count=1`. 리플리카 2대 이상에서 한 대가 죽는 경우는 안 봤다
3. 재기동은 `docker stop/start` 다 — 호스트 재부팅·OOM kill 등 다른 죽음은 안 봤다
4. 포지션 기반 복제다. GTID 켠 무대에서 같은지는 안 봤다
5. D 는 따로 돌아 A·B·C 와 판 순서 균형이 없다(§2)

## 5. 운영으로 옮기면 (제안 — 이 프로젝트 운영엔 리플리카가 없다)

- 리플리카 반동기는 **시작 옵션/옵션 파일**로 켠다(`plugin-load-add` + `loose-...enabled=ON`).
  `SET GLOBAL` 은 재기동에 사라지고, `SET PERSIST` 만으로는 이 무대에서 복귀하지 않았다
- 그래도 재기동 뒤엔 **소스 `Rpl_semi_sync_source_status`·`clients` 를 확인**하고, 아니면 리플리카
  IO 스레드를 재시작한다. 알람은 `status=OFF` 와 `no_tx` 증가에 건다 — 이 실험의 A·C 는 에러 없이
  비동기로 계속 돌았다

## 6. 재현

```bash
bash semisync_recovery.sh                                   # A·B·C 10판
OUT="$PWD/_out_armD" COMPOSE_OVERRIDE=docker-compose.armD.yml ORDER="D D D D" bash semisync_recovery.sh
OUT="$PWD/_diag" ORDER="C" KEEP=1 bash semisync_recovery.sh  # C 진단 — 컨테이너 남김, 끝나면 compose down -v
```
