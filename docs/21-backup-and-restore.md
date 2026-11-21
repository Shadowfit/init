# MySQL 백업·복구 (소규모 베타용)

`scripts/backup/` 의 스크립트 둘로 **하루 한 번 논리 백업**을 뜨고, **되살려지는지 확인**한다. 소규모 베타(데이터가 작고 호스트 한 대)를 가정한 최소선이다.

> **이 문서가 약속하는 것과 못 하는 것**
>
> | | |
> |---|---|
> | ✅ 잃는 선 | 마지막 백업 이후 **최대 하루치**(주기를 줄이면 그만큼 준다) |
> | ✅ 백업이 쓸 수 있는지 | 확인 모드로 임시 DB 에 부어 본다 — 파일이 있다는 것과 쓸 수 있다는 것은 다르다 |
> | ❌ 사고 직전 시각 복구(PITR) | 안 된다. binlog 가 필요하다 — [`decisions/backup-restore-rto-rpo.md`](./decisions/backup-restore-rto-rpo.md) |
> | ❌ 호스트를 잃었을 때 | 백업이 같은 호스트에만 있으면 같이 사라진다. 밖으로 복사해야 한다(아래 «호스트 밖으로») |
> | ❌ 복구 **시간**(RTO) | **이 스크립트로는 잰 적이 없다.** 논리 덤프는 물리 백업보다 느리다(위 문서의 실측으로 약 7배). 베타 데이터 크기에서 직접 재서 아래 표에 적을 것 |

## 파일

| 파일 | 하는 일 |
|---|---|
| `scripts/backup/mysql-backup.sh` | `shadowfit-mysql` 컨테이너를 `mysqldump --single-transaction` 으로 떠서 `.sql.gz` 로 저장. 끝까지 갔는지·압축이 멀쩡한지·너무 작지 않은지 검사하고, 보관 개수를 넘는 옛 파일을 지운다 |
| `scripts/backup/mysql-restore.sh` | 덤프를 되살린다. **기본은 확인 모드**(임시 DB 에 부어 테이블·행 수를 보여 주고 지움). `--into <db>` 는 실제 복구 |
| `scripts/backup/test-backup-restore.sh` | 일회용 MySQL 컨테이너로 위 둘을 왕복 시험(40건). CI(`backup-scripts-test.yml`)가 이 파일이 바뀔 때 돌린다 |

비밀번호는 호스트 명령행·환경에 올리지 않는다 — 컨테이너 안의 `MYSQL_ROOT_PASSWORD` 를 컨테이너 안에서만 쓴다.

## 설정

호스트(EC2)에서 하루 한 번. 예: root 의 crontab(`sudo crontab -e`)

```cron
# 매일 03:10 — 오래된 것은 14개까지 보관(기본값)
10 3 * * * BACKUP_DIR=/var/backups/shadowfit /opt/shadowfit/scripts/backup/mysql-backup.sh >> /var/log/shadowfit-backup.log 2>&1
```

| 환경변수 | 기본 | 뜻 |
|---|---|---|
| `MYSQL_CONTAINER` | `shadowfit-mysql` | 덤프할 컨테이너 |
| `MYSQL_DATABASE` | `shadowfit` | 덤프할 DB |
| `BACKUP_DIR` | `/var/backups/shadowfit` | 보관 위치 |
| `BACKUP_KEEP` | `14` | 최신 N 개만 남김 |
| `BACKUP_S3_URI` | (없음) | 예: `s3://버킷/shadowfit` — 설정하면 백업 직후 복사한다 |
| `BACKUP_MIN_BYTES` | `1024` | 이보다 작으면 실패로 본다(빈 DB 를 뜬 경우) |

종료 코드: `0` 성공 · `1` 실패 · `2` 이미 실행 중 · `3` 로컬은 성공했으나 S3 전송 실패.

**실패를 알아채는 방법은 이 스크립트에 없다.** 위 cron 은 로그만 남긴다. 최소한 `/var/log/shadowfit-backup.log` 의 마지막 줄과 `ls -l /var/backups/shadowfit` 의 날짜를 주 1회 눈으로 볼 것. 외부 알림(백업이 안 돌면 알려 주는 서비스)을 붙이는 것은 별도 일이다.

**디스크:** 보관 개수 × 덤프 크기만큼 쓴다. `pose_data` 가 가장 빨리 커지는 표다. 처음 몇 주는 덤프 크기를 보면서 `BACKUP_KEEP` 와 디스크를 정할 것.

## 호스트 밖으로 (S3)

`BACKUP_S3_URI` 를 설정하면 백업 직후 `aws s3 cp` 로 복사한다.

**실제 AWS 로 확인한 것 (2026-11-21)**

| | 결과 |
|---|---|
| 실패 경로 | ✅ 진짜 aws CLI·진짜 S3 에 요청이 가서 `PutObject` 가 `AccessDenied` 로 거절됐고, 스크립트는 **종료 코드 3**으로 끝났으며 **로컬 백업은 남았다** |
| 성공 경로 | ⬜ **못 했다.** 그때 쓴 자격 증명(`shadowfit-loadtest-temp`)이 읽기만 되고 쓰기는 안 돼서(`s3:PutObject` 불허) 올라가는 것까지 보지 못했다 |
| Windows 주의 | `aws.exe` 는 Git Bash 식 경로(`/c/Users/…`)를 못 읽는다(`path does not exist`). 운영 호스트는 리눅스라 해당 없고, 윈도에서 시험할 때만 `BACKUP_DIR=C:/Users/…` 로 줄 것 |

**성공 경로는 아직 미확인이다.** 쓰기가 되는 자격 증명(인스턴스 역할 권장)으로 첫 운영 실행 때 확인할 것:
1. 호스트에 aws CLI 가 있고 자격 증명이 있는가(인스턴스 역할 권장 — 키를 파일로 두지 않는다)
2. 해당 버킷에 `s3:PutObject` 가 허용되는가(복구용으로 `s3:GetObject`·`ListBucket` 도 필요하다)
3. 종료 코드가 `0` 이고 버킷에 파일이 실제로 올라왔는가

## 복구를 «확인»한다 (월 1회 권장)

```bash
# 가장 최근 덤프를 임시 DB 에 부어 보고 지운다 — 운영 DB 는 건드리지 않는다
LATEST=$(ls -1 /var/backups/shadowfit/shadowfit-*.sql.gz | sort | tail -1)
/opt/shadowfit/scripts/backup/mysql-restore.sh "$LATEST"
```

출력에서 볼 것: ① `부음 완료: N초` — **이 값이 논리 복구 시간의 하한이다.** 적어 둘 것 ② 테이블 수와 행 수가 운영과 비슷한가 ③ `마지막 Flyway 마이그레이션` 이 현재 배포 버전과 맞는가 ④ 마지막 줄 `확인 완료`.

| 날짜 | 덤프 크기 | 부음 시간(초) | 행 합계 | 비고 |
|---|---|---|---|---|
| (첫 확인 때 채울 것) | | | | |

## 실제 복구 (호스트를 잃었을 때)

새 호스트에서 운영 compose 를 올리고 MySQL 만 뜬 상태에서 한다. **백엔드는 아직 올리지 않는다** — 복구 중에 쓰기가 들어오면 덤프 시점과 섞인다.

1. 새 호스트에 `docker-compose.prod.yml` 과 `.env` 를 준비하고 `docker compose up -d mysql` 로 MySQL 만 올린다.
2. S3(또는 보관해 둔 곳)에서 가장 최근 덤프를 내려받는다.
3. `mysql-restore.sh <덤프> --into shadowfit` — 빈 DB 면 `--force` 없이 된다.
4. 출력의 행 수와 Flyway 버전을 확인한다.
5. 백엔드를 올린다. 덤프 시점보다 새 마이그레이션이 있으면 기동 로그에서 Flyway 가 올리는 것을 확인한다.
6. 사용자에게 «마지막 백업 이후의 기록은 사라졌다» 를 알린다(RPO = 백업 주기).

이 절차는 **순서대로 한 번 직접 해 본 적이 없다.** 새 호스트를 만드는 단계(2~3분)는 위 시험에서 다루지 않았다. 베타를 열기 전에 한 번은 해 볼 것(`decisions/recovery-drill.md` 가 같은 목적의 더 큰 훈련을 설계해 두었다).

## 시험

```bash
bash scripts/backup/test-backup-restore.sh   # docker 필요. 약 2분
```

한글·이모지·BLOB·파티션 테이블·트리거·함수가 왕복 뒤에 그대로인지, 덮어쓰기 보호·보관 개수·잠금·잘못된 입력을 본다. **S3 경로와, 같은 초에 두 번 도는 경우(같은 파일 이름을 덮어쓴다 — 하루 한 번이면 일어나지 않는다)는 시험하지 않는다.**
