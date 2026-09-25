@echo off
REM =========================================================
REM  Check that start-all.bat can find the AI secrets.
REM
REM  Run this whenever ai-service starts but /ai-lab returns 401 or refuses to
REM  save a key: both symptoms mean one of these two values is empty, and an
REM  empty secret fails SILENTLY (the service looks healthy).
REM
REM  Prints ONLY lengths and status - never the secret values themselves.
REM  ASCII-only. Safe to run any time.
REM
REM  Expected on a configured machine:
REM    AI_INTERNAL_SECRET  : loaded, 64 chars   (whatever length your .env has)
REM    AI_SECRET_MASTER_KEY: loaded, 44 chars
REM =========================================================
setlocal enabledelayedexpansion
call "%~dp0read-ai-env.bat"

set "V1=%AI_INTERNAL_SECRET%"
set "V2=%AI_SECRET_MASTER_KEY%"
call :report "AI_INTERNAL_SECRET" "%V1%"
call :report "AI_SECRET_MASTER_KEY" "%V2%"
exit /b 0

:report
set "NAME=%~1"
set "VAL=%~2"
set /a N=0
:loop
if defined VAL (
    set "VAL=!VAL:~1!"
    set /a N+=1
    goto loop
)
if %N%==0 (echo   %NAME%: MISSING) else (echo   %NAME%: loaded, %N% chars)
exit /b 0
