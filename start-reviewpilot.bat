@echo off
setlocal
cd /d "%~dp0"

echo Starting or stopping ReviewPilot...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"

echo.
echo ReviewPilot startup command finished.
pause
