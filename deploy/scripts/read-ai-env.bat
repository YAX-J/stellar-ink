@echo off
REM =========================================================
REM  Stellar Ink - load AI secrets from stellar-ink-ai\.env into THIS session
REM
REM  Called by start-all.bat as:  call read-ai-env.bat
REM
REM  Design note (this file replaced two earlier attempts):
REM    * `powershell.exe ... -File x.ps1` inside cmd's backquoted `for /f` body
REM      failed in several variants - a QUOTED program path gets split on its
REM      spaces, and extra flags after -NoProfile broke it again. Debugging
REM      cmd/PowerShell quoting is a poor use of time, and a silent failure here
REM      is expensive: ai-service starts, then rejects every signed call to
REM      Python with 401 while looking perfectly healthy.
REM    * So the reading is done HERE, and this script finishes by CALLING a tiny
REM      generated batch file that contains the `set` commands. The parent never
REM      parses a secret; the generated file is deleted immediately.
REM
REM  Only the two keys ai-service needs are exported, and only when unset:
REM  a value already present in the environment (container/CI) wins.
REM  No secret is ever echoed - a console keeps a transcript.
REM  ASCII-only file (same convention as start-all.bat).
REM =========================================================

set "AI_ENV_FILE=%~dp0..\..\stellar-ink-ai\.env"
if not exist "%AI_ENV_FILE%" (
    echo   note: %AI_ENV_FILE% not found - AI secrets must come from the environment
    exit /b 0
)

REM  Direct the generator into a temp file. Written with `>>` append after a
REM  first `>` that creates it, so a stale file can never leak old values.
set "AI_ENV_GEN=%TEMP%\stellar-ink-ai-env-%RANDOM%%RANDOM%.bat"
break > "%AI_ENV_GEN%"

REM  findstr /b matches at the beginning of a line; the name plus '=' is passed
REM  via /c: so it is treated as a literal (no regex surprises with '_').
REM  `tokens=1,* delims==` splits KEY and VALUE, and `*` keeps every '=' inside
REM  the base64 value intact.
for %%K in (AI_INTERNAL_SECRET AI_SECRET_MASTER_KEY) do (
    if not defined %%K (
        for /f "usebackq tokens=1,* delims==" %%a in (
            `findstr /b /c:"%%K=" "%AI_ENV_FILE%"`
        ) do (
            if not "%%b"=="" echo set "%%K=%%b">> "%AI_ENV_GEN%"
        )
    )
)

REM  Hand the assignments to the parent via call. `if exist` keeps an empty file
REM  from being executed (call on an empty .bat is harmless, but explicit is
REM  clearer when reading a transcript).
if exist "%AI_ENV_GEN%" call "%AI_ENV_GEN%"
del "%AI_ENV_GEN%" >nul 2>&1

if not defined AI_INTERNAL_SECRET (
    echo   note: AI_INTERNAL_SECRET not found in %AI_ENV_FILE%
)
if not defined AI_SECRET_MASTER_KEY (
    echo   note: AI_SECRET_MASTER_KEY not found in %AI_ENV_FILE%
)
exit /b 0
