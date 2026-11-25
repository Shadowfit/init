# `joint_coordinates` 는 off-page 로 저장되는가 — 아니다, 행 안에 있다 (2026-10-02)

측정일: 2026-10-02 (로컬, 일회용 `mysql:8.0` 컨테이너 — 8.0.46, 페이지 16KB, 행 형식 기본값 DYNAMIC)
rig: [`gen_offpage.py`](./gen_offpage.py) — `loadtest/ghz/batch.json` 의 실제 좌표 JSON 을 꺼내 SQL 을 만든다
원본: [`result.txt`](./result.txt)

---

## 왜 쟀나

[`realmysql-experiments.md`](../../../docs/portfolio/realmysql-experiments.md) 와 코드 주석(`PoseFrameProjection`·`PoseDataRepository`)은
projection 이 빨라진 이유를 이렇게 적는다.

> `joint_coordinates`(2.3KB JSON)가 InnoDB **off-page(overflow) 저장**이라 SELECT 시 추가 random I/O, projection 이 회피

DYNAMIC 행 형식에서 긴 컬럼이 별도 페이지로 나가는 것은 **행이 페이지의 절반쯤(16KB 페이지에서 약 8KB)에 안 들어갈 때**다.
2.3KB 행이 거기 해당하는지는 **아무도 안 쟀다.** 그래서 쟀다.

## 방법

- `pose_data` 와 같은 컬럼·같은 PK `(id, created_at)`·같은 보조 인덱스로 표를 7개 만들고, 표마다 JSON 크기만 다르게 2,000행씩 넣었다
- `real_1809` 는 부하 테스트 페이로드의 좌표 JSON 그대로다. 나머지는 같은 JSON 에 `pad` 문자열을 붙여 키웠다
- 판정은 **버퍼풀의 페이지 종류**로 한다(`information_schema.INNODB_BUFFER_PAGE`). off-page 로 나갔으면 `LOB_*` 페이지가 생긴다
- **양성 대조군**: 큰 JSON(8KB 이상)을 같이 넣었다. 이 팔에서 `LOB_*` 가 안 잡히면 «0» 은 장치가 못 본 것이지 없는 것이 아니다

## 결과

| 팔 | JSON 저장 크기 (`JSON_STORAGE_SIZE`) | `INDEX` 페이지 | `LOB_*` 페이지 | 리프당 행 수 |
|---|---:|---:|---:|---:|
| **`real_1809`** (실제 좌표) | **2,645 B** | 408 | **0** | **5.0** |
| `pad_2300` | 3,145 B | 508 | **0** | 4.0 |
| `pad_4000` | 4,845 B | 675 | **0** | 3.0 |
| `pad_7000` | 7,845 B | 1,010 | **0** | 2.0 |
| `pad_8200` | 9,045 B | 18 | **2,000** | — |
| `pad_9000` | 9,845 B | 18 | **2,000** | — |
| `pad_12000` | 12,845 B | 18 | **2,000** | — |

### 읽으면

1. **실제 좌표 JSON 은 행 안에 저장된다.** `LOB_*` 페이지가 0 이고 리프 페이지 하나에 행이 5개뿐이다 — 행이 JSON 을 품고 있다는 뜻이다
2. **경계는 저장 크기 7,845 B 와 9,045 B 사이다.** 페이지 절반(약 8KB)이라는 문서상 기준과 맞는다. 실제 좌표(2.6KB)는 그 3분의 1 이다
3. **양성 대조군이 잡혔다.** 8KB 를 넘긴 세 팔은 행마다 `LOB_FIRST` 페이지가 하나씩(2,000개) 생기고 `INDEX` 페이지가 18개로 줄었다 — 장치는 off-page 를 본다

## 그래서 무엇이 바뀌나

- **수치는 안 바뀐다.** projection 의 payload −98.7%·warm 8배는 잰 값 그대로다
- **«왜» 가 바뀐다.** JSON 이 행 안에 있으면 projection 을 써도 **읽는 페이지는 같다.** 이득은 «off-page I/O 회피» 가 아니라
  **«JSON 을 꺼내 앱으로 보내지 않는 것»**(서버의 JSON→텍스트 변환·전송·앱의 문자열 생성)에서 나온 것으로 봐야 한다.
  ⚠️ 그 몫이 실제로 얼마인지는 **안 쟀다** — 이 측정은 «어디에 저장되나» 만 답한다
- **projection 이 못 고치는 것이 분명해진다.** 세션 프레임을 훑는 쿼리는 projection 이어도 **JSON 이 든 페이지를 그대로 밟는다** —
  페이지당 5행이라 750행 세션은 리프 약 150장이다. 이걸 줄이는 것은 projection 이 아니라 **JSON 을 그 표에서 빼는 것**이다

## 한계

- **운영 표를 직접 본 것이 아니다.** 떠 있는 ShadowFit MySQL 이 없어 일회용 컨테이너에 같은 정의로 만들었다.
  `V1__baseline.sql` 과 `docker-compose.yml` 은 행 형식을 따로 지정하지 않으므로 기본값(DYNAMIC)이라고 봤다 —
  실제 인스턴스에서 `information_schema.tables.ROW_FORMAT` 을 확인한 것은 아니다
- **파티션을 걸지 않았다.** 행이 어디에 저장되는지는 행 단위 결정이라 파티션과 무관하다고 보고 뺐다
- **JSON 표본이 하나다**(부하 테스트 페이로드, 텍스트 1,809자). 문서가 말하는 «실제 2.1~2.3KB» 는 `pad_2300` 팔이 덮고, 경계까지는 여유가 3배다
- off-page 팔의 `n_leaf_pages` 통계(2,011)는 LOB 페이지를 같이 세므로 «리프당 행 수» 로 읽으면 안 된다. 그래서 그 칸을 비웠다
- AWS 재검증(29~41배)이 로컬(8배)보다 큰 이유는 이 측정으로 설명되지 않는다

## 재현

```bash
docker run -d --name sf-offpage-check -e MYSQL_ROOT_PASSWORD=pw mysql:8.0 --innodb-buffer-pool-size=512M
python loadtest/results/json-offpage-check-2026-10-02/gen_offpage.py loadtest/ghz/batch.json offpage.sql
# 초기화가 끝난 뒤(TCP 로 붙을 때까지 대기 — 소켓은 임시 서버에도 붙어 일찍 깨진다)
docker cp offpage.sql sf-offpage-check:/tmp/ && docker exec sf-offpage-check sh -c 'mysql -h127.0.0.1 -uroot -ppw --table < /tmp/offpage.sql'
docker rm -f sf-offpage-check
```
