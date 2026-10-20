@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Start-DemoPages.ps1 -PublishFrontend -OpenBrowser
if errorlevel 1 pause
