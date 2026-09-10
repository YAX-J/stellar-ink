@echo off
REM =========================================================
REM  Stellar Ink - stop all local services (Windows)
REM  Kills whatever listens on service ports and, if present,
REM  a LOCAL nacos started from tools\nacos (ports 8848/9848).
REM  ASCII-only file (no Chinese) to avoid codepage issues.
REM =========================================================

setlocal
echo Stopping services on ports 8080 / 8101-8106, and local nacos 8848/9848 if any ...
call :kill_port 8080
call :kill_port 8101
call :kill_port 8102
call :kill_port 8103
call :kill_port 8104
call :kill_port 8105
call :kill_port 8106
call :kill_port 8848
call :kill_port 9848
echo Done.
endlocal
exit /b 0

:kill_port
set FOUND=0
for /f "tokens=5" %%p in ('netstat -aon ^| findstr "LISTENING" ^| findstr ":%1 "') do (
    echo   port %1 : killing PID %%p
    taskkill /F /PID %%p >nul 2>&1
    set FOUND=1
)
if %FOUND%==0 echo   port %1 : nothing listening
exit /b 0
