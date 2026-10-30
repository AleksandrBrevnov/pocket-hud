@echo off
cd /d "%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_and_push.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Script finished with error code %ERRORLEVEL%.
)

echo.
echo Press any key to exit...
pause >nul
