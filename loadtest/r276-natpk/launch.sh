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
launch(){ # 타입 단계 상한(분) 디스크GB 이름
  local ud; ud=$(mktemp)
  sed -e "s|__REF__|$SHA|" -e "s|__PHASES__|$2|" -e "s|__RUN_ID__|$RUN_ID|" -e "s|__DEADLINE_MIN__|$3|" "$HERE/ec2_round.sh" > "$ud"
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
echo "RUN_ID=$RUN_ID SHA=$SHA AMI=$AMI"
launch c7i.xlarge "1"   360 100 natpk-insert
launch m6i.xlarge "2 3" 600 300 natpk-rebuild
