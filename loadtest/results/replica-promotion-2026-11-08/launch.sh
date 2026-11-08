#!/bin/bash
# 리플리카 승격 라운드 EC2 1대(m6i.xlarge) — 사용: bash launch.sh <SHA>   (DRY=1 이면 --dry-run)
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHA=${1:?SHA 가 필요하다}
git ls-remote origin | grep -q "^$SHA" || { echo "🔴 SHA $SHA 가 원격에 없다 — 먼저 푸시"; exit 1; }
RUN_ID=promote-ec2-$(date -u +%Y%m%d-%H%M%S)
REGION=ap-northeast-2
AMI=$(aws ec2 describe-images --region $REGION --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023*-kernel-6.12-x86_64" "Name=state,Values=available" \
  --query "reverse(sort_by(Images,&CreationDate))[:1].ImageId" --output text)
ud=$(mktemp)
sed -e "s|@@SHA@@|$SHA|" -e "s|@@RUN_ID@@|$RUN_ID|" -e "s|@@CAP_MIN@@|150|" "$HERE/ec2_userdata.sh" > "$ud"
grep -qE '@@[A-Z_]+@@' "$ud" && { echo "🔴 채우지 않은 자리표시자가 있다"; exit 1; }
t_api=$(date +%s%3N)
id=$(aws ec2 run-instances --region $REGION --image-id "$AMI" --instance-type m6i.xlarge \
  --key-name shadowfit-measure --security-group-ids sg-0382132d2f8b5f6ce --subnet-id subnet-0f9ea5dacb7260d34 \
  --iam-instance-profile Name=shadowfit-measure \
  --instance-initiated-shutdown-behavior terminate \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":200,"VolumeType":"gp3","DeleteOnTermination":true}}]' \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Project,Value=shadowfit-measure},{Key=RunId,Value=$RUN_ID}]" \
                       "ResourceType=volume,Tags=[{Key=Project,Value=shadowfit-measure}]" \
  --user-data "$(cat "$ud")" ${DRY:+--dry-run} --query 'Instances[0].InstanceId' --output text 2>&1) || { echo "$id"; [ -n "${DRY:-}" ] && exit 0; exit 1; }
echo "RUN_ID=$RUN_ID id=$id t_api=$t_api"
