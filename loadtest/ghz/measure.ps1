<#
  3-way before/after 측정 하네스 (load-test-strategy.md §7.5).
  매 측정마다 동일 상태로 리셋 → ramp(JSON) → 요약 파싱. 공정 비교용.

  사용:
    $env:INTERNAL_API_TOKEN = "<token>"
    .\measure.ps1 -Label before          # ramp 측정 + results\ramp-before.json
    .\measure.ps1 -Label config          # 설정만 적용 후
    .\measure.ps1 -Label jdbc            # JdbcTemplate 적용 후
    .\measure.ps1 -Label before -Summarize   # 측정 없이 기존 결과 재요약

  전제: 백엔드 :6565, mysql 컨테이너 shadowfit-mysql, batch.json(R=25, session 801).
#>
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [string]$Target = "localhost:6565",
  [string]$DataFile = "batch.json",
  [switch]$Summarize,          # 측정 건너뛰고 기존 json 만 요약
  [switch]$SkipReset,          # 801 행 리셋 건너뛰기
  [int]$WarmupSec = 0          # 본측정 전 warmup 부하 시간(초). 0 이면 생략. 공정비교용 JVM/풀 워밍업
)

# native exe(mysql/docker/ghz) 가 stderr 로 경고를 내면 PowerShell 5.1 이 NativeCommandError 로
# 승격시켜 스크립트를 죽인다(World-writable config 경고 등). 측정 스크립트라 Continue 로 두고
# 핵심 실패는 $LASTEXITCODE 로 명시 체크한다.
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
$results = Join-Path $here "results"
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Path $results | Out-Null }
$out = Join-Path $results "ramp-$Label.json"

function Show-Summary($path, $label) {
  $j = Get-Content $path -Raw | ConvertFrom-Json
  $err = 0
  if ($j.errorDistribution) { $j.errorDistribution.PSObject.Properties | ForEach-Object { $err += $_.Value } }
  $okPct = if ($j.count) { 100.0 * $j.statusCodeDistribution.OK / $j.count } else { 0 }
  function P($pc) { ($j.latencyDistribution | Where-Object { $_.percentage -eq $pc }).latency / 1e6 }
  "==== $label ===="
  "count   : {0}" -f $j.count
  "RPS     : {0:N1}" -f $j.rps
  "p50     : {0:N0} ms" -f (P 50)
  "p90     : {0:N0} ms" -f (P 90)
  "p95     : {0:N0} ms" -f (P 95)
  "p99     : {0:N0} ms" -f (P 99)
  "avg     : {0:N0} ms" -f ($j.average / 1e6)
  "slowest : {0:N0} ms" -f ($j.slowest / 1e6)
  "OK      : {0} / {1} ({2:N2}%)" -f $j.statusCodeDistribution.OK, $j.count, $okPct
  "errors  : {0}" -f $err
  if ($err -gt 0) { $j.statusCodeDistribution.PSObject.Properties | Where-Object { $_.Name -ne 'OK' } | ForEach-Object { "  - {0}: {1}" -f $_.Name, $_.Value } }
  ""
}

if ($Summarize) { Show-Summary $out "$Label (재요약)"; return }

if (-not $env:INTERNAL_API_TOKEN) { Write-Error "INTERNAL_API_TOKEN 미설정."; exit 1 }

# ghz 경로 (.bin 우선)
$ghz = "ghz"
if (-not (Get-Command ghz -ErrorAction SilentlyContinue)) {
  $bin = Join-Path (Split-Path -Parent $here) ".bin\ghz.exe"
  if (Test-Path $bin) { $ghz = $bin } else { Write-Error "ghz 없음"; exit 1 }
}

# 메타데이터 파일
$metaFile = Join-Path $results "metadata.json"
[System.IO.File]::WriteAllText($metaFile, ('{"authorization":"Bearer ' + $env:INTERNAL_API_TOKEN + '"}'), (New-Object System.Text.UTF8Encoding($false)))

# 1) 동일 상태 리셋 — session 801 의 누적 행 삭제 (더미 세션 row 는 보존)
if (-not $SkipReset) {
  Write-Host "[reset] DELETE pose_data WHERE session_id=801 ..." -ForegroundColor Yellow
  docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "DELETE FROM pose_data WHERE session_id=801;" 2>$null | Out-Null
  $cnt = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e "SELECT COUNT(*) FROM pose_data WHERE session_id=801;" 2>$null | Select-Object -Last 1)
  Write-Host "[reset] 남은 행: $cnt" -ForegroundColor Yellow
}

# 1.5) warmup — JVM JIT·커넥션풀 워밍업 (공정비교: cold/warm 차이 제거). 결과는 버림.
if ($WarmupSec -gt 0) {
  Write-Host "[$Label] warmup ${WarmupSec}s (c=20, 결과 폐기)..." -ForegroundColor DarkYellow
  & $ghz --insecure --call ExerciseService.SavePoseDataBatch `
    --metadata-file $metaFile --data-file $DataFile `
    -c 20 -z "${WarmupSec}s" $Target *> $null
  # warmup 이 적재한 행 제거 → 본측정 클린 상태 보장
  docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "DELETE FROM pose_data WHERE session_id=801;" 2>$null | Out-Null
  $wc = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e "SELECT COUNT(*) FROM pose_data WHERE session_id=801;" 2>$null | Select-Object -Last 1)
  Write-Host "[$Label] warmup 완료, 본측정 전 행: $wc" -ForegroundColor DarkYellow
}

# 2) ramp (baseline 과 동일 파라미터: 동시성 5→100 step, 210s)
Write-Host "[$Label] ramp 측정 시작 (210s)..." -ForegroundColor Cyan
& $ghz --insecure --call ExerciseService.SavePoseDataBatch `
  --metadata-file $metaFile --data-file $DataFile `
  --concurrency-schedule=step --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 `
  --concurrency-step-duration=10s -z 210s `
  -O json -o $out $Target
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out)) { Write-Error "ghz 실패 (exit=$LASTEXITCODE)"; exit 1 }
Write-Host "[$Label] 완료 → $out" -ForegroundColor Green
""

# 3) 요약
Show-Summary $out $Label
