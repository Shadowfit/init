#!/usr/bin/env bash
# SQL Server (LocalDB) version of the admin-index selectivity sweep. Seeding gate -> sweep -> raw export.
# usage: bash run.sh            (defaults: 1,000,000 sessions / 200,000 members / 6 blocks x 5 reps)
set -euo pipefail
cd "$(dirname "$0")"
S='(localdb)\MSSQLLocalDB'
DB=${DB:-sf_idx_bench}
SESSIONS=${SESSIONS:-1000000}
USERS=${USERS:-200000}
BLOCKS=${BLOCKS:-6}
REPS=${REPS:-5}
mkdir -p raw

{
  echo "date: $(date -Iseconds)"
  sqlcmd -S "$S" -E -h -1 -W -Q "SET NOCOUNT ON; SELECT @@VERSION; SELECT CONCAT('edition=', SERVERPROPERTY('Edition'), ' cpu_count=', (SELECT cpu_count FROM sys.dm_os_sys_info), ' physical_mem_mb=', (SELECT physical_memory_kb/1024 FROM sys.dm_os_sys_info));"
  echo "SESSIONS=$SESSIONS USERS=$USERS BLOCKS=$BLOCKS REPS=$REPS DB=$DB"
} > raw/env.txt

echo "[1/3] seed + gate"
# -b: abort on error. The gate THROWs when seeding does not have the properties the experiment assumes -> set -e stops here.
sqlcmd -S "$S" -E -b -W -i setup_seed.sql -v DB="$DB" SESSIONS="$SESSIONS" USERS="$USERS" | tee raw/seed_gate.log

echo "[2/3] sweep"
sqlcmd -S "$S" -E -b -W -i run_sweep.sql -v DB="$DB" BLOCKS="$BLOCKS" REPS="$REPS" | tee raw/sweep.log

echo "[3/3] export"
{
  echo "blk,arm,status,window_d,kind,rep,us,reads"
  sqlcmd -S "$S" -E -d "$DB" -h -1 -s "," -W -Q "SET NOCOUNT ON; SELECT blk,arm,status,window_d,kind,rep,us,reads FROM dbo.bench_raw ORDER BY blk,arm,status,window_d,kind,rep;"
} > raw/raw.csv
wc -l raw/raw.csv
