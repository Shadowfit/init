#!/bin/bash
# 앱 박스 — dnsmasq(후보 B 의 DNS) + 백엔드(bootstrap ROLE=app) + 탐침 + 지휘(failover.sh).
# 규칙: 커밋 SHA 고정 · grep 단언 · 감시견(CAP_MIN) · terminate. 끝나면 DB 두 대도 내린다.
# 대문자 자리표시자(골뱅이 두 쌍으로 감싼 것)는 launch.sh 가 채운다.
set -uo pipefail
SHA=@@SHA@@
RUN_ID=@@RUN_ID@@
CAP_MIN=@@CAP_MIN@@
DB1=@@DB1@@
DB2=@@DB2@@
TRIALS='@@TRIALS@@'                 # 비우면 failover.sh 기본(16판). 스모크는 launch.sh 가 짧게 넣는다
BURN=@@BURN@@
S3=s3://shadowfit-measure-055447613012/shadowfit/$RUN_ID
RAW=https://raw.githubusercontent.com/Shadowfit/init/$SHA/loadtest/results/failover-app-reconnect-2026-11-08
RIG=/root/rig
OUT=/root/rig_out
LOG=/root/round.log
NAME=db.failover.internal
exec > >(tee -a "$LOG") 2>&1
mkdir -p "$RIG" "$OUT"

up() { aws s3 cp "$LOG" "$S3/app.round.log" --only-show-errors || true; aws s3 sync "$OUT" "$S3/out" --only-show-errors || true; }
finish() {
  echo "== finish: $1 ($(date -Is))"
  [ -f /root/app.log ] && cp /root/app.log "$OUT/app.full.log"
  up
  aws s3 cp - "$S3/DONE" <<< "$1 $(date -Is)" || true
  for h in "$DB1" "$DB2"; do ssh -i /root/.ssh/rig -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=5 "root@$h" 'shutdown -h now' || true; done
  aws s3 cp - "$S3/FINISHED" <<< "$1 $(date -Is)" || true
  shutdown -h now
}
echo "== start $(date -Is) SHA=$SHA RUN_ID=$RUN_ID CAP_MIN=$CAP_MIN DB1=$DB1 DB2=$DB2 TRIALS=$TRIALS BURN=$BURN"
( sleep $((CAP_MIN * 60)); echo "🔴 상한 ${CAP_MIN}분 도달 — 끈다"; finish CAP ) &
( while sleep 300; do up; done ) &

# ── SSH 키 → S3 (DB 박스가 받아 간다) ──
ssh-keygen -q -t ed25519 -N '' -f /root/.ssh/rig || finish KEYGEN
aws s3 cp /root/.ssh/rig.pub "$S3/app_pubkey" --only-show-errors || finish PUBKEY_UPLOAD

# ── rig 파일 (커밋 SHA 고정) ──
cd /root
for f in failover.sh probe.py analyze.py; do curl -fsSL "$RAW/$f" -o "$RIG/$f" || finish "RIG_DOWNLOAD_$f"; done
grep -q 'promoted_after_kill_ms' "$RIG/failover.sh" || finish RIG_ASSERT
curl -fsSL "https://raw.githubusercontent.com/Shadowfit/init/$SHA/loadtest/aws/bootstrap.sh" -o bootstrap.sh || finish BOOTSTRAP_DOWNLOAD
grep -q 'if \[ "$ROLE" = "app" \]' bootstrap.sh || finish BOOTSTRAP_ASSERT

# ── 후보 B 의 DNS: dnsmasq (TTL 0) — 앱 박스의 모든 이름 풀이가 여기를 지난다 ──
dnf -y -q install dnsmasq >/dev/null || finish DNSMASQ_INSTALL
cat > /etc/dnsmasq.d/failover.conf <<EOF
listen-address=127.0.0.1
bind-interfaces
no-resolv
server=169.254.169.253
addn-hosts=/etc/failover-hosts
local-ttl=0
EOF
grep -q '^conf-dir=/etc/dnsmasq.d' /etc/dnsmasq.conf || echo 'conf-dir=/etc/dnsmasq.d/,*.conf' >> /etc/dnsmasq.conf
echo "$DB1 $NAME" > /etc/failover-hosts
# 🔴 스모크 1회차(11-08)가 여기서 죽었다 — $NAME 이 안 풀렸다. 그때 세운 추정(검증 안 됨): nsswitch 의
#    `resolve` 가 systemd-resolved 를 다시 깨워(disable 만으로는 소켓 활성화가 남는다) dnsmasq 를 우회했다.
#    resolved 를 mask 하고, nsswitch 에서 resolve 를 빼서 «glibc → resolv.conf → dnsmasq» 한 길만 남긴다.
systemctl mask --now systemd-resolved systemd-resolved.socket systemd-resolved-varlink.socket systemd-resolved-monitor.socket 2>/dev/null || true
sed -i -E '/^hosts:/ s/resolve( \[[^]]*\])?//' /etc/nsswitch.conf
rm -f /etc/resolv.conf; printf 'nameserver 127.0.0.1\noptions timeout:1\n' > /etc/resolv.conf
systemctl enable --now dnsmasq || finish DNSMASQ_START
dns_diag() { echo "nsswitch: $(grep '^hosts:' /etc/nsswitch.conf)"; echo "resolved: $(systemctl is-active systemd-resolved)";
             echo "getent: $(getent hosts $NAME)"; cat /etc/failover-hosts; journalctl -u dnsmasq --no-pager | tail -5; }
# 🔴 스모크 2회차는 위 정리 뒤에도 같은 자리에서 죽었는데, 진단(바로 아래)에선 이름이 제대로 풀렸다 — dnsmasq 가
#    «Started» 를 찍은 같은 초에 첫 조회를 던진 경합이었다. 1회차도 같은 원인이었을 수 있다(위 추정은 검증 안 됨).
#    그래서 최대 30초 다시 묻는다.
dns_ok=0
for _ in $(seq 1 30); do
  getent hosts github.com >/dev/null && [ "$(getent hosts $NAME | awk '{print $1}')" = "$DB1" ] && { dns_ok=1; break; }
  sleep 1
done
dns_diag
[ "$dns_ok" = 1 ] || finish DNS_BROKEN

# ── DB 두 대 준비 기다림 ──
for _ in $(seq 1 240); do
  aws s3 ls "$S3/READY.db1" >/dev/null 2>&1 && aws s3 ls "$S3/READY.db2" >/dev/null 2>&1 && break
  sleep 5
done
aws s3 ls "$S3/READY.db2" >/dev/null 2>&1 && aws s3 ls "$S3/READY.db1" >/dev/null 2>&1 || finish DB_NOT_READY
export DB1 DB2 OUT BURN
[ -n "$TRIALS" ] && export TRIALS
bash "$RIG/failover.sh" prep || finish PREP_FAIL

# ── 백엔드 (bootstrap ROLE=app — JDK·빌드·systemd 유닛·헬스 대기). 앱은 DNS 이름으로 원본에 붙는다 ──
ROLE=app REF=$SHA DB_HOST=$NAME bash bootstrap.sh || finish BOOTSTRAP_FAIL
{ echo "instance_type=$(curl -s -H "X-aws-ec2-metadata-token: $(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" http://169.254.169.254/latest/meta-data/instance-type)"; nproc; free -g; } > "$OUT/box.txt"
up

bash "$RIG/failover.sh" run > "$OUT/run.log" 2>&1
echo "== rig exit $?"
finish DONE
