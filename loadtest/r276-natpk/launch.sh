#!/bin/bash
# #276 ㄷ 비용 측정 EC2 2대 — 사용: bash launch.sh <SHA>   (DRY=1 이면 --dry-run)
#   박스 1 c7i.xlarge · PHASES="1"   (① 삽입 — 08-23 uk-bufferpool 과 같은 타입) · 상한 360분
#   박스 2 m6i.xlarge · PHASES="2 3" (② 재구성·③ 읽기 — 08-12 online-ddl 과 같은 타입) · 상한 600분
#     ② 는 2.1KB/행 × 1,000만 행 ≈ 21GB 표를 원본·복사본·재구성본·binlog 로 몇 벌 들고 있어 디스크 300GB
#     시딩이 한 줄(세션 하나씩 커밋)이라 로컬 i3 에서 0.64초/세션 — EC2 속도를 모르니 상한을 넉넉히 둔다
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHA=${1:?SHA 가 필요하다}
git ls-remote origin | grep -q "^$SHA" || { echo "🔴 SHA $SHA 가 원격에 없다 — 먼저 푸시"; exit 1; }
RUN_ID=natpk-ec2-$(date -u +%Y%m%d-%H%M%S)
REGION=ap-northeast-2
AMI=$(aws ec2 describe-images --region $REGION --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023*-kernel-6.12-x86_64" "Name=state,Values=available" \
  --query "reverse(sort_by(Images,&CreationDate))[:1].ImageId" --output text)
launch(){ # 타입 단계 상한(분) 디스크GB 이름 [rig 설정 «export K=V ...»]
  local ud; ud=$(mktemp)
  sed -e "s|__REF__|$SHA|" -e "s|__PHASES__|$2|" -e "s|__RUN_ID__|$RUN_ID|" -e "s|__DEADLINE_MIN__|$3|" "$HERE/ec2_round.sh"     | awk -v e="${6:-}" '$0=="__ENV__"{print e; next} {print}' > "$ud"
  grep -qE '__[A-Z_]+__' "$ud" && { echo "🔴 채우지 않은 자리표시자가 있다"; exit 1; }
  local id
  id=$(aws ec2 run-instances --region $REGION --image-id "$AMI" --instance-type "$1" \
    --key-name shadowfit-measure --security-group-ids sg-0382132d2f8b5f6ce --subnet-id subnet-0f9ea5dacb7260d34 \
    --iam-instance-profile Name=shadowfit-measure \
    --instance-initiated-shutdown-behavior terminate \
    --block-device-mappings "[{\"DeviceName\":\"/dev/xvda\",\"Ebs\":{\"VolumeSize\":$4,\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Project,Value=shadowfit-measure},{Key=RunId,Value=$RUN_ID},{Key=Name,Value=$5}]" \
                         "ResourceType=volume,Tags=[{Key=Project,Value=shadowfit-measure}]" \
    --user-data "$(cat "$ud")" ${DRY:+--dry-run} --query 'Instances[0].InstanceId' --output text 2>&1) || { echo "$id"; [ -n "${DRY:-}" ] && return 0; exit 1; }
  echo "$5 $1 id=$id"
}
echo "RUN_ID=$RUN_ID SHA=$SHA AMI=$AMI MODE=${MODE:-first}"
case ${MODE:-first} in
  first)   # 11-08 첫 라운드
    launch c7i.xlarge "1"   360 100 natpk-insert
    launch m6i.xlarge "2 3" 600 300 natpk-rebuild ;;
  rerun)   # 11-09 재측정 — 11-08 은 팔 N 이 매판 1799 로 실패하고 상한에 걸려 ③ 을 못 돌렸다
    # 박스 A: pt-osc 로 자연키 표를 한 번만 만들고 바로 ③ 읽기 (P 는 11-08 에 3판 성공해서 ② 쪽 숫자는 이미 있다)
    launch m6i.xlarge "2 3" 360 300 natpk-reads "export SEQ='r1:P'"
    # 박스 B: 팔 N(쓰기 있음)·N0(쓰기 없음)만. 상한 RERUN_MIN 분
    #   ONLINE_LOG_BYTES — 11-08 관측: 기본 128MiB 가 writer(0.2초마다 20행) 아래 약 5,967초에 찼다 → 약 22.5KB/s(약 81MB/시간).
    #   N 한 판이 상한 전체를 써도 안 차게 «81MB/시간 × 상한 시간» 을 GiB 로 올림. 운영 권장값이 아니라 rig 이 끝까지 돌게 하는 값이다
    #   WRITER_SECS — writer 가 DDL 보다 먼저 끝나면 뒤쪽은 쓰기 없는 DDL 이 된다 → 상한과 같게
    RERUN_MIN=${RERUN_MIN:-960}
    log_gib=$(( (RERUN_MIN * 81 / 60 + 1023) / 1024 ))
    launch m6i.xlarge "2" "$RERUN_MIN" 300 natpk-native       "export SEQ='discard:N0 r1:N r2:N0 r3:N r4:N0 r5:N' ONLINE_LOG_BYTES=$(( log_gib * 1073741824 )) WRITER_SECS=$(( RERUN_MIN * 60 ))" ;;
esac
