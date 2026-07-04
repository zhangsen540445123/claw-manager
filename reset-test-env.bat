@echo off
setlocal EnableExtensions DisableDelayedExpansion

cd /d "%~dp0"

call :load_env_if_missing WEB_HOST_PORT
call :load_env_if_missing API_HOST_PORT
call :load_env_if_missing ADMIN_EMAIL
call :load_env_if_missing ADMIN_PASSWORD
call :load_env_if_missing OPENCLAW_RUNNER_IMAGE
call :load_env_if_missing RESET_ADMIN_PASSWORD
call :load_env_if_missing RESET_INSTANCE_1_NAME
call :load_env_if_missing RESET_INSTANCE_2_NAME

if not defined WEB_HOST_PORT set "WEB_HOST_PORT=4300"
if not defined API_HOST_PORT set "API_HOST_PORT=8080"
if not defined ADMIN_EMAIL set "ADMIN_EMAIL=admin@example.com"
if not defined ADMIN_PASSWORD set "ADMIN_PASSWORD=ChangeMe123!"
if not defined OPENCLAW_RUNNER_IMAGE set "OPENCLAW_RUNNER_IMAGE=ghcr.io/zhangsen540445123/claw-manager-openclaw-runner:latest"
if not defined RESET_ADMIN_PASSWORD set "RESET_ADMIN_PASSWORD=cxf123..."
if not defined RESET_INSTANCE_1_NAME set "RESET_INSTANCE_1_NAME=OpenClaw Test 1"
if not defined RESET_INSTANCE_2_NAME set "RESET_INSTANCE_2_NAME=OpenClaw Test 2"

set "COMPOSE=docker compose -f compose.yaml"
if exist "compose.local.yaml" set "COMPOSE=docker compose -f compose.yaml -f compose.local.yaml"

echo ========================================
echo  Claw Manager full reset
echo ========================================
echo.
echo This will stop project containers, delete Docker volumes, delete .\data,
echo rebuild runner/API/Web images, start services, seed presets, create instances,
echo install plugins, and restart gateways.

call :check_docker || goto fail

echo.
echo [Step 1/7] Stopping compose services and deleting volumes...
%COMPOSE% down -v
if errorlevel 1 goto fail

echo.
echo [Step 2/7] Removing OpenClaw instance containers...
call :remove_openclaw_containers || goto fail

echo.
echo [Step 3/7] Deleting local data directory...
call :delete_data_dir || goto fail

echo.
echo [Step 4/7] Building OpenClaw runner image...
call :build_runner_image || goto fail

echo.
echo [Step 5/7] Building API/Web images...
%COMPOSE% build api web
if errorlevel 1 goto fail

echo.
echo [Step 6/7] Starting services...
%COMPOSE% up -d
if errorlevel 1 goto fail

echo.
echo [Step 7/7] Seeding and provisioning test workspace...
if not exist "scripts\reset-test-env-bootstrap.ps1" (
  echo [ERROR] Missing scripts\reset-test-env-bootstrap.ps1.
  goto fail
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\reset-test-env-bootstrap.ps1" -ApiHostPort "%API_HOST_PORT%" -WebHostPort "%WEB_HOST_PORT%" -AdminEmail "%ADMIN_EMAIL%" -AdminPassword "%ADMIN_PASSWORD%" -ResetAdminPassword "%RESET_ADMIN_PASSWORD%" -Instance1Name "%RESET_INSTANCE_1_NAME%" -Instance2Name "%RESET_INSTANCE_2_NAME%"
if errorlevel 1 goto fail

call :print_summary
call :pause_exit
exit /b 0

:remove_openclaw_containers
for /f "usebackq delims=" %%I in (`docker ps -aq --filter "name=clawbot-openclaw"`) do (
  echo   Removing %%I
  docker rm -f %%I >nul
  if errorlevel 1 exit /b 1
)
for /f "usebackq delims=" %%I in (`docker ps -aq --filter "name=claw-manager-openclaw"`) do (
  echo   Removing %%I
  docker rm -f %%I >nul
  if errorlevel 1 exit /b 1
)
echo [OK] OpenClaw instance container cleanup finished.
exit /b 0

:delete_data_dir
if exist "data\" (
  echo [INFO] Deleting %CD%\data
  rmdir /s /q "data"
  if exist "data\" (
    echo [ERROR] Failed to delete .\data.
    exit /b 1
  )
  echo [OK] Local data directory deleted.
) else (
  echo [INFO] Local data directory does not exist.
)
exit /b 0

:build_runner_image
echo [INFO] Runner image tag: %OPENCLAW_RUNNER_IMAGE%
docker build -f containers/openclaw-runner/Dockerfile -t "%OPENCLAW_RUNNER_IMAGE%" .
if errorlevel 1 exit /b 1
exit /b 0

:check_docker
docker compose version >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker Compose is not available. Start Docker Desktop and try again.
  exit /b 1
)
exit /b 0

:print_summary
echo.
echo ========================================
echo  Done
echo ========================================
echo.
echo Admin URL:
echo   http://127.0.0.1:%WEB_HOST_PORT%
echo.
echo Admin login after reset:
echo   Email:    %ADMIN_EMAIL%
echo   Password: %RESET_ADMIN_PASSWORD%
echo.
echo Test instances:
echo   %RESET_INSTANCE_1_NAME%
echo   %RESET_INSTANCE_2_NAME%
echo.
echo Note:
echo   Seeded model/OpenViking secrets are not printed here.
echo.
exit /b 0

:fail
echo.
echo [ERROR] Script failed.
call :pause_exit
exit /b 1

:pause_exit
echo.
echo Press any key to exit...
pause >nul
exit /b 0

:load_env_if_missing
set "ENV_KEY=%~1"
call set "ENV_CURRENT=%%%ENV_KEY%%%"
if defined ENV_CURRENT exit /b 0
if not exist ".env" exit /b 0
for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
  if /I "%%A"=="%ENV_KEY%" set "%ENV_KEY%=%%B"
)
exit /b 0
