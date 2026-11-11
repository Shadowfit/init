#!/bin/bash
# DB 박스(원본·리플리카 공통) — docker 와 mysql:8.0 이미지만 준비하고, 앱 박스가 SSH 로 조종할 수 있게 연다.
# MySQL 은 여기서 안 띄운다 — failover.sh 가 판마다 새로 띄운다.
# 규칙: 커밋 SHA 고정 · 감시견(CAP_MIN) · terminate — binlog-replay-time-2026-11-07/ec2_userdata.sh 와 같다.
# 대문자 자리표시자(골뱅이 두 쌍으로 감싼 것)는 launch.sh 가 채운다.
set -uo pipefail
RUN_ID=@@RUN_ID@@
NODE=@@NODE@@
CAP_MIN=@@CAP_MIN@@
S3=s3://shadowfit-measure-055447613012/shadowfit/$RUN_ID
LOG=/root/round.log
exec > >(tee -a "$LOG") 2>&1
up() { aws s3 cp "$LOG" "$S3/$NODE.round.log" --only-show-errors || true; }
finish() { echo "== finish: $1 ($(date -Is))"; up; shutdown -h now; }
echo "== start $(date -Is) node=$NODE RUN_ID=$RUN_ID CAP_MIN=$CAP_MIN"
( sleep $((CAP_MIN * 60)); echo "🔴 상한 ${CAP_MIN}분 도달 — 끈다"; finish CAP ) &
# 앱 박스가 끝을 알리면(또는 앱 박스가 죽어 SSH 종료가 안 오면 감시견이) 내려간다
( while sleep 30; do aws s3 ls "$S3/DONE" >/dev/null 2>&1 && finish DONE_MARK; done ) &

dnf -y -q install docker iptables-nft >/dev/null || finish PKG_FAIL
systemctl enable --now docker || finish DOCKER_FAIL
docker pull -q mysql:8.0 || finish PULL_FAIL

# 앱 박스의 공개키를 root 로 받는다 (AL2023 cloud-init 은 root 키에 강제명령을 붙여 두므로 별도 줄로 추가)
echo 'PermitRootLogin prohibit-password' > /etc/ssh/sshd_config.d/00-rig.conf
systemctl reload sshd
mkdir -p /root/.ssh; chmod 700 /root/.ssh
for _ in $(seq 1 240); do
  aws s3 cp "$S3/app_pubkey" /root/app_pubkey --only-show-errors 2>/dev/null && break
  sleep 5
done
[ -s /root/app_pubkey ] || finish NO_PUBKEY
cat /root/app_pubkey >> /root/.ssh/authorized_keys; chmod 600 /root/.ssh/authorized_keys
{ echo "instance_type=$(curl -s -H "X-aws-ec2-metadata-token: $(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" http://169.254.169.254/latest/meta-data/instance-type)"; nproc; free -g; docker --version; } > /root/box.txt
aws s3 cp /root/box.txt "$S3/$NODE.box.txt" --only-show-errors
aws s3 cp - "$S3/READY.$NODE" <<< "$(date -Is)"
echo "== ready $(date -Is)"
up
wait
