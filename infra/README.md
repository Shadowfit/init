# infra — ShadowFit 운영 호스트

단일 EC2 노드 1대를 Terraform 으로 만들고, main 병합 후 이미지가 GHCR 에 올라가면 GitHub Actions 가 그 호스트에 자동 반영한다.

> ⚠️ **이 디렉터리의 코드는 아직 실제 AWS 에 적용해 본 적이 없다.** `terraform validate`·`plan` 까지만 확인 대상이고,
> `apply` → 배포 → 롤백 훈련을 마치기 전까지는 "돌려봤다"고 말하지 않는다. 훈련 결과는 `docs/decisions/` 에 기록한다.

## 구성

```
GitHub Actions ──OIDC──► IAM 역할 ──► S3(번들) + SSM Run Command
                                              │
EC2 (AL2023, c7i.xlarge) ◄────────────────────┘
 ├─ docker compose  (docker-compose.prod.yml + 서비스별 태그 덮어쓰기)
 ├─ /mnt/shadowfit  ← 별도 EBS(gp3, xfs): MySQL 데이터, 기준 영상
 └─ 설정·시크릿     ← SSM Parameter Store (/shadowfit/prod/*)
```

| 파일 | 역할 |
|---|---|
| `terraform/network.tf` | VPC·서브넷·보안그룹 (8080, 8000 만 개방. SSH·3306·gRPC 닫힘) |
| `terraform/ec2.tf`, `user_data.sh.tftpl` | 호스트, IMDSv2, Docker·compose, 데이터 디스크 마운트 |
| `terraform/storage.tf` | 번들 버킷, 데이터 EBS(인스턴스와 분리) |
| `terraform/iam.tf` | 호스트 역할, GitHub OIDC 역할(main 브랜치만) |
| `host/deploy.sh` | 호스트에서 도는 배포·헬스체크·롤백 |
| `../.github/workflows/deploy.yml` | 번들 업로드 → SSM 실행 |

## 설계 결정

- **SSH 를 열지 않는다.** 접속·배포 모두 SSM. 키 관리와 22번 포트가 없다.
- **시크릿은 Terraform 이 만들지 않는다.** 값이 state 에 남지 않게 사람이 SSM 에 넣고, 호스트가 배포 때 읽어 `.env` 로 만든다.
- **AWS 장기 키가 없다.** GitHub OIDC, 신뢰 조건은 `repo:Shadowfit/init:ref:refs/heads/main`.
- **서비스별 이미지 태그.** backend·ai 이미지는 경로 필터로 따로 빌드돼 같은 커밋에 둘 다 있다는 보장이 없다.
  그래서 `IMAGE_TAG` 하나를 쓰지 않고 `deploy.sh` 가 서비스별로 덮어쓰며, 안 바뀐 쪽은 `state.env` 의 현재 태그를 유지한다.
- **데이터 볼륨 분리.** 인스턴스를 교체해도 MySQL 데이터가 남는다. 이미 포맷된 볼륨은 다시 포맷하지 않는다.
- **메모리 한도는 값을 박지 않고 유도한다.** AI = 708MiB + 106.74MiB × `concurrent_sessions`
  (`docs/decisions/detector-pool-ceiling-formula.md`). 백엔드 2048MiB·버퍼풀 1G 는 기존 기본값을 옮긴 것이고 **이 호스트에서 잰 값이 아니다.**

## 처음 켜는 순서

사전 준비: AWS 계정, `aws configure`(또는 SSO), Terraform ≥ 1.6, GHCR 패키지 공개 여부 확인.

1. **인프라 생성**
   ```powershell
   cd infra/terraform
   copy terraform.tfvars.example terraform.tfvars   # 필요하면 수정
   terraform init
   terraform plan -out tfplan                        # 만들어질 리소스를 먼저 읽는다
   terraform apply tfplan
   ```
2. **시크릿 넣기** — 필수 키는 `.env.example` 과 `docs/19-deployment.md §2.1` 참조. 아래 값은 예시 자리표시자다.
   ```powershell
   aws ssm put-parameter --type SecureString --name /shadowfit/prod/JWT_SECRET --value "<값>"
   ```
   필수: `MYSQL_ROOT_PASSWORD` `MYSQL_USER` `MYSQL_PASSWORD` `DB_USERNAME` `DB_PASSWORD` `JWT_SECRET`
   `INTERNAL_API_TOKEN` `AI_PUBLIC_TOKEN`(INTERNAL 과 달라야 함). 선택: `OPENAI_API_KEY` `EXPO_ACCESS_TOKEN`.
   GHCR 패키지가 비공개면 `GHCR_USER`·`GHCR_TOKEN`(read:packages)도 넣는다.
   ⚠️ 값에 `$` 가 있어도 `deploy.sh` 가 이스케이프하지만, 되도록 피한다.
3. **GitHub 저장소 변수** (Settings → Variables). `terraform output` 에서 가져온다.
   `AWS_DEPLOY_ROLE_ARN` · `DEPLOY_INSTANCE_ID` · `DEPLOY_BUCKET` · `AWS_REGION` · 마지막에 `DEPLOY_ENABLED=true`
4. **첫 배포** — Actions → "Deploy — prod 호스트" → Run workflow. 첫 배포는 `backend_tag`·`ai_tag` 를 둘 다 준다(`sha-<40자>`).
5. **확인** — `http://<public_ip>:8080`, `http://<public_ip>:8000/health`. 호스트 로그는 SSM 세션으로 `docker logs`.

이후에는 main 병합 → 이미지 푸시 → 자동 배포.

## 롤백

`deploy.sh` 가 헬스체크·스모크 실패 시 직전 태그로 자동 롤백한다. 수동으로 하려면 Run workflow 에 이전 태그를 넣는다.

**롤백이 못 하는 것**: Flyway 마이그레이션. 이미지를 되돌려도 이미 적용된 스키마는 그대로라, 새 이미지가 만든 스키마를 옛 이미지가
못 읽을 수 있다. 스키마 변경은 *확장(컬럼 추가) → 코드 전환 → 수축(컬럼 제거)* 으로 릴리스를 나눠 낸다.

## 비용 줄이기

상시 운영이 아니면 계산 자원만 내린다. 데이터 EBS·번들 버킷·IAM 은 남는다.

```powershell
terraform destroy -target=aws_instance.app -target=aws_eip.app -target=aws_volume_attachment.data
terraform apply        # 다시 올릴 때
```

⚠️ 인자 없는 `terraform destroy` 는 데이터 볼륨까지 지운다.
⚠️ 다시 올리면 호스트가 새로 만들어지므로 `deploy.sh` 의 `state.env` 가 없다 → 첫 배포처럼 태그를 둘 다 지정한다.

## 백업

배포할 때마다 `deploy.sh` 가 `scripts/backup/` 의 스크립트와 systemd timer 를 설치한다. 매일 03:10 KST(18:10 UTC)에
`mysqldump --single-transaction` 덤프를 떠서 호스트 `/var/backups/shadowfit` 에 14개 보관하고, **백업 버킷**(`terraform output backup_bucket`)에도 올린다.
호스트 역할은 버킷에 **쓰기만** 되고 읽기·삭제는 안 된다. 복구는 사람이 자기 AWS 자격으로 내려받아 한다.

- 확인: SSM 세션에서 `systemctl list-timers shadowfit-backup.timer`, `journalctl -u shadowfit-backup`
- 수동 실행: `sudo systemctl start shadowfit-backup.service`
- ⚠️ **S3 전송 경로는 아직 실제로 돌려 보지 못했다.** 스크립트 머리말도 같은 말을 한다. 첫 배포 직후 수동 실행해서 버킷에 파일이 생기는지 본다.
- ⚠️ 이것은 **논리 백업**이다: RPO = 하루, PITR 아님, 큰 DB 에서는 복구가 느리다(물리 백업의 약 7배, `docs/decisions/backup-restore-rto-rpo.md`).

### 복구 훈련 (한 번은 해 볼 것)

덤프가 있다는 것과 되살려진다는 것은 다르다. 첫 배포 후 아래를 실제로 하고 **걸린 시간과 확인한 행 수**를 `docs/decisions/` 에 적는다.

1. 수동 백업 실행 → S3 에 파일이 생겼는지 확인
2. 호스트에서 확인 모드: `/opt/shadowfit/backup/mysql-restore.sh <덤프>` (임시 DB 에 부어 테이블·행 수를 보여 주고 지운다)
3. 선택: 새 호스트를 `terraform apply` 로 띄우고 S3 덤프로 복구해 걸린 시간 측정(RTO)

## 알람 (outbox FAILED)

재시도를 소진해 `FAILED` 가 된 outbox 행(탈퇴 후 개인정보 삭제·AI 통보 등)을 사람이 알아야 한다. 흐름:

```
호스트 timer(1분) → CloudWatch ShadowFit/OutboxFailedNew → 알람 → SNS → Lambda → Slack/Discord 웹훅
```

- 호스트의 `shadowfit-outbox-metric.timer` 가 백엔드의 `shadowfit_outbox_failed` 게이지를 읽어 두 지표를 올린다
  (`infra/host/alarm/publish-outbox-failed.sh`). `OutboxFailed` 는 누적, `OutboxFailedNew` 는 **새로 늘어난 수**다.
- 알람은 **증가분**에 건다. FAILED 행은 90일 보관이라 누적값은 오래 0 이 안 되고, 그러면 두 번째 실패는 알림이 없다.
- 알람이 둘이다: `*-outbox-failed-new`(새 실패 발생) · `*-outbox-metric-missing`(지표가 15분째 안 옴 = 위 알람이 눈먼 상태).
- 비용: 사용자 지정 지표 2개 + 알람 2개 + Lambda 로 월 1달러 안팎(Lambda·SNS 는 이 호출량에서 무료 구간).

**웹훅 URL 은 사람이 넣는다**(Terraform 은 시크릿을 만들지 않는다). 경로가 `/shadowfit/prod/` 가 아닌 점에 주의 — 그 아래는
`deploy.sh` 가 전부 앱 환경변수로 읽어 들이므로 URL 이 백엔드 컨테이너로 샌다.

```powershell
aws ssm put-parameter --type SecureString --name /shadowfit/alerts/webhook-url --value "<Slack 또는 Discord 웹훅 URL>"
```

**첫 적용 후 반드시 한 번 시험한다** — 알람이 «울리는 것»을 본 적이 없으면 울린다고 말할 수 없다:

```powershell
aws cloudwatch set-alarm-state --alarm-name shadowfit-prod-outbox-failed-new --state-value ALARM --state-reason "알람 시험"
```

웹훅 채널에 🔴 메시지가 오면 끝이다. 안 오면 `aws logs tail /aws/lambda/shadowfit-prod-alert-forwarder` 로 본다
(웹훅 URL 미등록이면 `ParameterNotFound`).

- ⚠️ **실제 AWS 에서 돌려 보지 못했다.** 스크립트와 Lambda 는 가짜 `docker`·`aws` 로 시험했고 Terraform 은 `validate` 까지만 했다.
- ⚠️ 한 분 안에 «정리로 N건 삭제 + 새 실패 N건» 이 겹치면 증가분이 0 으로 보인다. 정리는 1시간마다 돌고 겹치려면 삭제와 새 실패가 같은 분에 일어나야 해서 드물며, `OutboxFailed` 에는 남는다.
- 위 시험의 `set-alarm-state` 는 상태만 잠시 바꾼다. 다음 평가 때 실제 값으로 되돌아간다.

## 아직 없는 것 (숨기지 않는다)

- **TLS·도메인.** 지금은 HTTP. `docs/decisions/reverse-proxy-and-tls.md` 가 미결정이라 건드리지 않았다. Caddy 나 ALB 는 다음 단계.
- **PITR·물리 백업(XtraBackup)·복구 훈련.** 위 논리 백업만 붙어 있다. binlog 보존과 물리 백업은 데이터가 커질 때.
- **모니터링.** `obs` 프로파일을 이 호스트에 올리는 일. 알람은 위 outbox FAILED **하나뿐**이다 — 백업 실패·백엔드 다운·pending 적체는 아직 없다.
- **HA.** 단일 노드다. DB 리플리카·수동 승격 훈련은 이 위에 얹는다.
- **상태 백엔드.** state 는 로컬. 팀이 같이 쓰기 전에 `versions.tf` 의 S3 backend 로 옮긴다.
