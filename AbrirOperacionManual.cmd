@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Start.ps1 -Environment Manual -OpenBrowser
if errorlevel 1 pause
