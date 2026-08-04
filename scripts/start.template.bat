@echo off
setlocal
set BASE_DIR=%~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%BASE_DIR%start.ps1" %*
endlocal
