<#
  ② 백엔드 격리 부하 테스트 (load-test-strategy.md §3.2) — SavePoseDataBatch gRPC.
  MediaPipe 를 건너뛰고 Spring+MySQL 적재 경로만 ghz 로 풀스로틀.

  사전조건 (README.md 참조):
    - 백엔드 gRPC 가 :6565 에서 reflection 켜진 채 떠 있음 (application.yml grpc.server.reflection-enabled: true)
    - $env:INTERNAL_API_TOKEN 설정 (서버와 동일 값)
    - 세션 row 존재 (data.sql 더미 801) — batch.json 의 sessionId 와 일치
    - ghz 설치 (README §설치)

  사용:
    $env:INTERNAL_API_TOKEN = "<server-token>"
    .\run-save-pose-batch.ps1 -Mode smoke      # 경로·인증 검증 (5 call)
    .\run-save-pose-batch.ps1 -Mode baseline   # 단일 세션 순차 — batch 1건 지연 분해
    .\run-save-pose-batch.ps1 -Mode ramp       # 동시성 step ramp — throughput 천장 + p99
#>
param(
  [ValidateSet("smoke", "baseline", "ramp")]
  [string]$Mode = "smoke",
  [string]$Target = "localhost:6565",
  [string]$DataFile = "batch.json"
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not $env:INTERNAL_API_TOKEN) {
  Write-Error "INTERNAL_API_TOKEN 미설정. `$env:INTERNAL_API_TOKEN = '<server-token>' 후 재실행."
  exit 1
}
if (-not (Get-Command ghz -ErrorAction SilentlyContinue)) {
  Write-Error "ghz 미설치. README.md §설치 참조 (scoop install ghz / go install)."
  exit 1
}
if (-not (Test-Path $DataFile)) {
  Write-Error "$DataFile 없음. gen_batch.py 또는 README 의 PowerShell 생성 블록으로 생성."
  exit 1
}

$results = Join-Path $here "results"
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Path $results | Out-Null }

$metadata = '{"authorization":"Bearer ' + $env:INTERNAL_API_TOKEN + '"}'
$call = "ExerciseService.SavePoseDataBatch"
$common = @(
  "--insecure",
  "--call", $call,
  "--metadata", $metadata,
  "--data-file", $DataFile
)

switch ($Mode) {
  "smoke" {
    Write-Host "[smoke] 경로·인증 검증 — 5 call, c=1" -ForegroundColor Cyan
    ghz @common -n 5 -c 1 $Target
  }
  "baseline" {
    Write-Host "[baseline] 단일 세션 순차 — 200 call, c=1 (batch 1건 지연 p50/95/99)" -ForegroundColor Cyan
    ghz @common -n 200 -c 1 -O html -o "$results\baseline.html" $Target
    Write-Host "리포트: $results\baseline.html" -ForegroundColor Green
  }
  "ramp" {
    Write-Host "[ramp] 동시성 step 5->100 (10s/step) — throughput 천장 + 콜백 p99" -ForegroundColor Cyan
    ghz @common `
      --concurrency-schedule=step `
      --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 `
      --concurrency-step-duration=10s `
      -z 210s `
      -O html -o "$results\ramp.html" $Target
    Write-Host "리포트: $results\ramp.html" -ForegroundColor Green
    Write-Host "→ throughput 가 평탄해지는 동시성 = 백엔드 천장. 그 지점 p99 를 SLO 와 비교 (doc §11)." -ForegroundColor Yellow
  }
}
