@echo off
REM =========================================================
REM  Stellar Ink - stop all local services (Windows)
REM  Kills whatever listens on service ports (4 java + the python
REM  uvicorn) and, if present, a LOCAL nacos started from
REM  tools\nacos (ports 8848/9848).
REM  ASCII-only file (no Chinese) to avoid codepage issues.
REM =========================================================

setlocal
echo Stopping services on ports 8080 / 8101 / 8102 / 8107 / 8200, and local nacos 8848/9848 if any ...
call :kill_port 8080
call :kill_port 8101
call :kill_port 8102
call :kill_port 8107
REM  8200 = python uvicorn, started by start-all.bat; without this line the
REM  stack would keep one orphan process that also blocks the next start.
call :kill_port 8200
call :kill_port 8848
call :kill_port 9848
echo Done.
echo Note: the python service is stopped by port, so it is covered here; if you
echo       started it manually in a console, that console window may stay open.
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
