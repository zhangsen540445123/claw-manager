@echo off
setlocal EnableExtensions DisableDelayedExpansion

cd /d "%~dp0"

call :load_env_if_missing WEB_HOST_PORT
call :load_env_if_missing ADMIN_EMAIL
call :load_env_if_missing ADMIN_PASSWORD

if not defined WEB_HOST_PORT set "WEB_HOST_PORT=4300"
if not defined ADMIN_EMAIL set "ADMIN_EMAIL=admin@example.com"
if not defined ADMIN_PASSWORD set "ADMIN_PASSWORD=ChangeMe123!"

if not exist "compose.local.yaml" (
  echo [ERROR] compose.local.yaml is required because it contains the local API/Web build definitions.
  echo [ERROR] This script is intended to rebuild local images, not pull remote images from compose.yaml.
  call :pause_exit
  exit /b 1
)

set "COMPOSE=docker compose -f compose.yaml -f compose.local.yaml"

echo ========================================
echo  Claw Manager rebuild API/Web
echo ========================================

call :check_docker || goto fail

echo.
echo [Step 1/2] Building API/Web images...
%COMPOSE% build api web
if errorlevel 1 goto fail

echo.
echo [Step 2/2] Recreating API/Web containers with the freshly built images...
%COMPOSE% up -d --no-deps --force-recreate api web
if errorlevel 1 goto fail

call :print_summary
call :pause_exit
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
echo Admin login defaults:
echo   Email:    %ADMIN_EMAIL%
echo   Password: %ADMIN_PASSWORD%
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
