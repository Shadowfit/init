#!/bin/bash
# 축 B orchestrator — 로컬에서 돈다. launch → (boot k 작업 → stop → start) × M → terminate.
set -u
export MSYS_NO_PATHCONV=1
REGION=ap-northeast-2
M=${M:-5}                                # stop→start 횟수 (부팅은 M+1 회)
SP=$(cd "$(dirname "$0")" && pwd)
RES=/e/init/loadtest/results/nonrepro-axisB-2026-09-14
LOG=$SP/orchestrate.log
KEY=~/.ssh/shadowfit-measure.pem
SSH_OPT="-i $KEY -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o LogLevel=ERROR"
mkdir -p "$RES"
log() { echo "[$(date -Is)] $*" | tee -a "$LOG"; }
aws() { command aws --region $REGION "$@"; }

IID=${IID:-}
if [ -z "$IID" ]; then
  log "launch c7i.4xlarge"
  IID=$(aws ec2 run-instances \
    --image-id ami-071eb8c676c4c4bf5 --instance-type c7i.4xlarge \
    --key-name shadowfit-measure --security-group-ids sg-0382132d2f8b5f6ce \
    --subnet-id subnet-0d7f507333c66b23d \
    --iam-instance-profile Name=shadowfit-measure \
    --instance-initiated-shutdown-behavior terminate \
    --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3","DeleteOnTermination":true}}]' \
    --metadata-options HttpTokens=required,InstanceMetadataTags=enabled \
    --tag-specifications \
      'ResourceType=instance,Tags=[{Key=Project,Value=shadowfit-measure},{Key=Name,Value=nonrepro-axisB}]' \
      'ResourceType=volume,Tags=[{Key=Project,Value=shadowfit-measure},{Key=Name,Value=nonrepro-axisB}]' \
    --user-data "file://$(cygpath -m "$SP/user-data.sh")" \
    --query 'Instances[0].InstanceId' --output text) || { log "🔴 launch 실패"; exit 1; }
  log "instance $IID"
  echo "$IID" > "$SP/instance-id"
fi

ip_of() { aws ec2 describe-instances --instance-ids "$IID" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text; }
wait_ssh() {  # $1 = ip
  local i; for i in $(seq 1 40); do ssh $SSH_OPT root@"$1" true 2>/dev/null && return 0; sleep 15; done; return 1
}
poll_file() {  # $1 ip  $2 remote file  $3 max-iterations(30s)  $4 fail-file
  local i; for i in $(seq 1 "$3"); do
    if ssh $SSH_OPT root@"$1" "test -f $2" 2>/dev/null; then return 0; fi
    if [ -n "${4:-}" ] && ssh $SSH_OPT root@"$1" "test -f $4" 2>/dev/null; then return 2; fi
    sleep 30
  done; return 1
}

aws ec2 wait instance-running --instance-ids "$IID"
IP=$(ip_of); log "running · ip=$IP"
# 부트스트랩 전엔 root 로그인이 안 열려 있다 — ec2-user 로 기다린다
for i in $(seq 1 40); do ssh $SSH_OPT ec2-user@"$IP" true 2>/dev/null && break; sleep 15; done
log "ssh up(ec2-user) · 부트스트랩 대기"
for i in $(seq 1 80); do
  ssh $SSH_OPT ec2-user@"$IP" "sudo test -f /root/BOOTSTRAP_DONE" 2>/dev/null && { BS=ok; break; }
  ssh $SSH_OPT ec2-user@"$IP" "sudo test -f /root/BOOTSTRAP_FAILED" 2>/dev/null && { BS=fail; break; }
  sleep 30
done
if [ "${BS:-}" != "ok" ]; then
  log "🔴 부트스트랩 ${BS:-timeout} — 로그 회수 후 박스는 남긴다(사람이 볼 것)"
  ssh $SSH_OPT ec2-user@"$IP" "sudo cat /var/log/axisB-userdata.log" > "$RES/userdata-failed.log" 2>&1
  exit 1
fi
log "부트스트랩 완료"

for K in $(seq 1 $((M+1))); do
  if [ "$K" -gt 1 ]; then
    log "boot$K: start-instances"
    aws ec2 start-instances --instance-ids "$IID" >/dev/null
    aws ec2 wait instance-running --instance-ids "$IID"
    IP=$(ip_of); log "boot$K: running · ip=$IP"
    wait_ssh "$IP" || { log "🔴 boot$K: ssh 불가 — 중단(박스는 남김)"; exit 1; }
  else
    wait_ssh "$IP" || { log "🔴 boot1: root ssh 불가"; exit 1; }
  fi
  log "boot$K: 작업 시작"
  ssh $SSH_OPT root@"$IP" "nohup setsid bash /root/axisB_boot.sh $K > /root/axisB/boot$K.log 2>&1 < /dev/null &"
  poll_file "$IP" "/root/axisB/boot$K/DONE" 90 "/root/axisB/WATCHDOG_FIRED"; rc=$?
  if [ $rc -ne 0 ]; then log "🔴 boot$K: DONE 없음(rc=$rc) — 중단(박스는 남김)"; exit 1; fi
  mkdir -p "$RES/boot$K"
  scp $SSH_OPT -r root@"$IP":/root/axisB/boot$K "$RES/" >/dev/null 2>&1 && log "boot$K: 회수 완료" || log "⚠️ boot$K: scp 실패 (S3 에는 있다)"
  scp $SSH_OPT root@"$IP":/root/axisB/boot$K.log "$RES/boot$K/" >/dev/null 2>&1
  [ "$K" = 1 ] && scp $SSH_OPT root@"$IP":"/root/axisB/calibration_bootstrap.tsv /root/axisB/axisB-userdata.log /root/ai_venv_conditions.txt" "$RES/" >/dev/null 2>&1
  grep -h "rps\|fps" "$RES/boot$K/run_all.log" 2>/dev/null | tail -3 | tee -a "$LOG"
  if [ "$K" -le "$M" ]; then
    log "boot$K: stop-instances"
    aws ec2 stop-instances --instance-ids "$IID" >/dev/null
    aws ec2 wait instance-stopped --instance-ids "$IID"
    log "boot$K: stopped"
  fi
done

log "terminate $IID"
aws ec2 terminate-instances --instance-ids "$IID" >/dev/null
aws ec2 wait instance-terminated --instance-ids "$IID"
log "terminated. 남은 볼륨: $(aws ec2 describe-volumes --filters Name=tag:Name,Values=nonrepro-axisB --query 'Volumes[].VolumeId' --output text)"
cp "$LOG" "$RES/orchestrate.log"
log "ALL DONE"
