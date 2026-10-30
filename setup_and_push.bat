@echo off
chcp 65001 >nul
title PocketHUD GitHub Setup and Push
cd /d "%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_and_push.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Скрипт завершился с ошибкой (код: %ERRORLEVEL%).
)

echo.
echo Нажмите любую клавишу для выхода...
pause >nul
