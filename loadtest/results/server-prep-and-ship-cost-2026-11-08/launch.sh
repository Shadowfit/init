#!/bin/bash
# 이 라운드의 EC2 를 띄운다 — 3대(m6i.xlarge): node1 은 준비 시간 + 반출 비용, node2·3 은 준비 시간만.
# 준비 시간은 «박스를 몇 번 띄워 봤나» 가 곧 반복 수라서 3대를 같은 방식으로 동시에 띄운다.
#
# 🔴 커밋 SHA 로 고정한다 — 이 SHA 가 GitHub 에 올라가 있어야 한다(브랜치 URL 은 CDN 캐시로 옛 파일을 준다).
# 사용: bash launch.sh <SHA>   (DRY=1 이면 --dry-run)
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHA=${1:?SHA 가 필요하다}
git ls-remote origin | grep -q "^$SHA" || { echo "🔴 SHA $SHA 가 원격에 없다 — 먼저 푸시"; exit 1; }
RUN_ID=shipcost-ec2-$(date -u +%Y%m%d-%H%M%S)
REGION=ap-northeast-2
AMI=$(aws ec2 describe-images --region $REGION --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023*-kernel-6.12-x86_64" "Name=state,Values=available" \
  --query "reverse(sort_by(Images,&CreationDate))[:1].ImageId" --output text)
SUBNET=subnet-0f9ea5dacb7260d34      # ap-northeast-2d (이전 라운드와 같은 AZ)
SG=sg-0382132d2f8b5f6ce
LAUNCH_LOG=$HERE/_launch_times.tsv
printf 'node\tinstance_id\tt_api_ms\tt_running_ms\n' > "$LAUNCH_LOG"
echo "RUN_ID=$RUN_ID AMI=$AMI SHA=$SHA"

for node in 1 2 3; do
  if [ "$node" = 1 ]; then ship=1; cap=150; vol=200; else ship=0; cap=40; vol=100; fi
  ud=$(mktemp)
  sed -e "s|@@SHA@@|$SHA|" -e "s|@@RUN_ID@@|$RUN_ID|" -e "s|@@CAP_MIN@@|$cap|" \
      -e "s|@@RUN_SHIP@@|$ship|" -e "s|@@NODE@@|node$node|" "$HERE/ec2_userdata.sh" > "$ud"
  grep -qE '@@[A-Z_]+@@' "$ud" && { echo "🔴 채우지 않은 자리표시자가 있다"; exit 1; }
  t_api=$(date +%s%3N)
  id=$(aws ec2 run-instances --region $REGION --image-id "$AMI" --instance-type m6i.xlarge \
    --key-name shadowfit-measure --security-group-ids $SG --subnet-id $SUBNET \
    --iam-instance-profile Name=shadowfit-measure \
    --instance-initiated-shutdown-behavior terminate \
    --block-device-mappings "[{\"DeviceName\":\"/dev/xvda\",\"Ebs\":{\"VolumeSize\":$vol,\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Project,Value=shadowfit-measure},{Key=RunId,Value=$RUN_ID}]" \
                         "ResourceType=volume,Tags=[{Key=Project,Value=shadowfit-measure}]" \
    --user-data "$(cat "$ud")" ${DRY:+--dry-run} --query 'Instances[0].InstanceId' --output text 2>&1) || { echo "$id"; [ -n "${DRY:-}" ] && continue; exit 1; }
  [ -n "${DRY:-}" ] && { echo "node$node dry-run: $id"; continue; }
  echo "node$node → $id"
  printf 'node%s\t%s\t%s\t\n' "$node" "$id" "$t_api" >> "$LAUNCH_LOG"
done
[ -n "${DRY:-}" ] && exit 0

# running 상태가 된 시각(호출 쪽에서 본 값) — 노드마다 병렬로 기다려야 순서가 값에 안 섞인다
tmpd=$(mktemp -d)
while IFS=$'\t' read -r node id t_api _; do
  ( aws ec2 wait instance-running --region $REGION --instance-ids "$id"; date +%s%3N > "$tmpd/$node" ) &
done < <(tail -n +2 "$LAUNCH_LOG")
wait
printf 'node\tinstance_id\tt_api_ms\tt_running_ms\n' > "$LAUNCH_LOG.new"
while IFS=$'\t' read -r node id t_api _; do
  printf '%s\t%s\t%s\t%s\n' "$node" "$id" "$t_api" "$(cat "$tmpd/$node")" >> "$LAUNCH_LOG.new"
done < <(tail -n +2 "$LAUNCH_LOG")
mv "$LAUNCH_LOG.new" "$LAUNCH_LOG"
aws s3 cp "$LAUNCH_LOG" "s3://shadowfit-measure-055447613012/shadowfit/$RUN_ID/launch_times.tsv" --only-show-errors
echo "RUN_ID=$RUN_ID"; cat "$LAUNCH_LOG"
