@echo off
REM 시연용 원클릭 기동 — Docker Desktop 이 켜져 있어야 한다.
REM   1) 격리 스택(mysql + backend:18080 + ai:18000) 기동  2) 프론트(Expo) 기동
REM 브라우저 시연: http://localhost:8081   /   폰 시연: Expo Go 로 터미널 QR 스캔
cd /d "%~dp0"
docker info >nul 2>&1
if errorlevel 1 (
  echo [!] Docker Desktop 이 꺼져 있습니다. 먼저 Docker Desktop 을 켜고 다시 실행하세요.
  pause
  exit /b 1
)
docker compose -p shadowfit-iso -f docker-compose.yml -f docker-compose.iso.yml up -d mysql shadowfit-backend shadowfit-ai ai-nginx
echo.
echo 백엔드가 뜰 때까지 약 30초 걸립니다. http://localhost:18080/swagger-ui/index.html 이 열리면 준비 완료.
echo.
cd frontend
call npx expo start
