#!/bin/bash
# 이 라운드의 EC2 3대(m6i.xlarge, 같은 AZ)를 띄운다 — db1(원본) · db2(리플리카) · app(백엔드·탐침·DNS·지휘)
# 🔴 커밋 SHA 로 고정한다 — 이 SHA 가 GitHub 에 올라가 있어야 한다(브랜치 URL 은 CDN 캐시로 옛 파일을 준다).
# 사용: bash launch.sh <SHA>                       본 라운드 (16판, 앞 4판 버림)
#       TRIALS="A:K1 B:K2" BURN=0 bash launch.sh <SHA>   스모크 (rig 결함만 거른다 — 값은 인용 안 함)
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHA=${1:?SHA 가 필요하다}
TRIALS=${TRIALS:-}
BURN=${BURN:-4}
git ls-remote origin | grep -q "^$SHA" || { echo "🔴 SHA $SHA 가 원격에 없다 — 먼저 푸시"; exit 1; }
RUN_ID=failover-ec2-$(date -u +%Y%m%d-%H%M%S)
REGION=ap-northeast-2
AMI=$(aws ec2 describe-images --region $REGION --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023*-kernel-6.12-x86_64" "Name=state,Values=available" \
  --query "reverse(sort_by(Images,&CreationDate))[:1].ImageId" --output text)
SUBNET=subnet-0f9ea5dacb7260d34      # ap-northeast-2d (이전 라운드와 같은 AZ)
SG=sg-0382132d2f8b5f6ce
CAP_MIN=${CAP_MIN:-360}
echo "RUN_ID=$RUN_ID AMI=$AMI SHA=$SHA TRIALS=${TRIALS:-기본} BURN=$BURN"

launch() {  # $1 이름 $2 user-data 파일 $3 볼륨GB → 인스턴스 ID 와 사설 IP
  aws ec2 run-instances --region $REGION --image-id "$AMI" --instance-type m6i.xlarge \
    --key-name shadowfit-measure --security-group-ids $SG --subnet-id $SUBNET \
    --iam-instance-profile Name=shadowfit-measure \
    --instance-initiated-shutdown-behavior terminate \
    --block-device-mappings "[{\"DeviceName\":\"/dev/xvda\",\"Ebs\":{\"VolumeSize\":$3,\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Project,Value=shadowfit-measure},{Key=RunId,Value=$RUN_ID},{Key=Name,Value=$RUN_ID-$1}]" \
                         "ResourceType=volume,Tags=[{Key=Project,Value=shadowfit-measure}]" \
    --user-data "$(cat "$2")" --query 'Instances[0].[InstanceId,PrivateIpAddress]' --output text
}

fill() { sed -e "s|@@RUN_ID@@|$RUN_ID|" -e "s|@@CAP_MIN@@|$CAP_MIN|" "$@"; }
ips=()
for n in db1 db2; do
  ud=$(mktemp); fill -e "s|@@NODE@@|$n|" "$HERE/db_userdata.sh" > "$ud"
  grep -qE '@@[A-Z_]+@@' "$ud" && { echo "🔴 채우지 않은 자리표시자 ($n)"; exit 1; }
  read -r id ip < <(launch "$n" "$ud" 50)
  echo "$n → $id $ip"; ips+=("$ip")
done
ud=$(mktemp)
fill -e "s|@@SHA@@|$SHA|" -e "s|@@DB1@@|${ips[0]}|" -e "s|@@DB2@@|${ips[1]}|" -e "s|@@TRIALS@@|$TRIALS|" -e "s|@@BURN@@|$BURN|" \
  "$HERE/app_userdata.sh" > "$ud"
grep -qE '@@[A-Z_]+@@' "$ud" && { echo "🔴 채우지 않은 자리표시자 (app)"; exit 1; }
read -r id ip < <(launch app "$ud" 50)
echo "app → $id $ip"
echo "RUN_ID=$RUN_ID"
