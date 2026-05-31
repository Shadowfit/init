<#
  재빌드(코드 변경 반영) → 컨테이너 교체 대기 → gRPC 기동 대기 → 801 리셋 → 측정.
  jdbc 단계 자동화용. 측정 직전 SkipReset 으로 measure.ps1 호출(리셋은 여기서 수동 DELETE).
  사용: .\rebuild-and-measure.ps1 -Label jdbc
#>
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [int]$MaxWaitSec = 1200,
  [int]$WarmupSec = 60         # 재빌드 직후 cold JVM 보정용 warmup (공정비교 필수)
)
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent (Split-Path -Parent $here)   # E:\init

$oldStart = (docker inspect -f '{{.State.StartedAt}}' shadowfit-backend 2>$null | Select-Object -Last 1)
Write-Host "[rebuild] 기존 StartedAt=$oldStart"

# 1) 재빌드 (컨테이너 내부 gradle)
Write-Host "[rebuild] docker compose up -d --build shadowfit-backend ..."
Push-Location $root
docker compose up -d --build shadowfit-backend 2>&1 | Select-Object -Last 3
Pop-Location

# 2) 컨테이너 교체 대기
Write-Host "[rebuild] 컨테이너 교체 대기..."
$elapsed = 0
do {
  Start-Sleep -Seconds 5; $elapsed += 5
  $now = (docker inspect -f '{{.State.StartedAt}}' shadowfit-backend 2>$null | Select-Object -Last 1)
  if ($elapsed -ge $MaxWaitSec) { Write-Error "[rebuild] 타임아웃"; exit 1 }
} while (-not $now -or $now -eq $oldStart)
Write-Host "[rebuild] 새 컨테이너: $now (대기 ${elapsed}s)"

# 3) gRPC 기동 대기 (smoke 폴링)
$token = $env:INTERNAL_API_TOKEN
$results = Join-Path $here "results"
$metaFile = Join-Path $results "metadata.json"
[System.IO.File]::WriteAllText($metaFile, ('{"authorization":"Bearer ' + $token + '"}'), (New-Object System.Text.UTF8Encoding($false)))
$ghz = Join-Path $root "loadtest\.bin\ghz.exe"
Write-Host "[rebuild] gRPC 기동 대기..."
$ready = $false; $w = 0
while (-not $ready -and $w -lt 300) {
  Start-Sleep -Seconds 5; $w += 5
  Set-Location $here
  & $ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file $metaFile --data-file "batch.json" -n 1 -c 1 localhost:6565 *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true }
}
if (-not $ready) { Write-Error "[rebuild] gRPC 기동 안 됨"; exit 1 }
Write-Host "[rebuild] gRPC ready (대기 ${w}s)."

# 4) 클린 리셋 (수동 DELETE — 확실하게)
Write-Host "[rebuild] 801 리셋..."
docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "DELETE FROM pose_data WHERE session_id=801;" 2>$null | Out-Null
$cnt = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e "SELECT COUNT(*) FROM pose_data WHERE session_id=801;" 2>$null | Select-Object -Last 1)
Write-Host "[rebuild] 남은 행: $cnt"
if ("$cnt".Trim() -ne "0") { Write-Error "[rebuild] 리셋 실패 (남은 행=$cnt)"; exit 1 }

# 5) 측정 (리셋 이미 했으니 SkipReset, warmup 으로 cold JVM 보정)
""
& (Join-Path $here "measure.ps1") -Label $Label -SkipReset -WarmupSec $WarmupSec
