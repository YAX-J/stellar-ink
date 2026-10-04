# Stellar Ink - follow every service log in ONE console (Windows)
#
# ⚠️ THIS FILE MUST STAY "UTF-8 WITH BOM". Do not let an editor rewrite it without
#    the BOM: Windows PowerShell 5.1 reads a BOM-less UTF-8 .ps1 as ANSI (GBK on
#    this machine), and the mis-decoded Chinese comments then swallow line breaks
#    - the parser reports a bogus "unexpected }" and the script refuses to run.
#    Measured: same bytes without BOM -> parse error at line 75; with BOM -> fine.
#    (Kept bilingual on purpose: this warning is the thing a future editor must see.)
#
# 用途：start-all.bat 在 SHOW_LOGS=1 模式下结束时调用它，把 4 个 java 服务 +
# python 的日志**汇总到同一个终端**，每行带服务前缀与颜色。
#
# 为什么不是「让服务直接往当前终端写」：cmd 没有 tee，多个后台进程共享同一个
# stdout 会变成**无前缀的交错文本**（5 个 JVM 同时启动时基本没法读）。所以走
# 「服务写文件 + 这里 tail」这条路：既有前缀，又不丢文件日志。
#
# 三条约定：
#   1. **Ctrl+C 只停止跟随，不停服务** —— 服务是 javaw / cmd 起的独立进程，
#      关掉这个终端它们照样跑；要停服务请用 stop-all.bat。
#   2. 文件按**行**读（logback 立即刷盘、python 端设了 PYTHONUNBUFFERED），
#      所以看到的就是实时的；用 -Encoding UTF8 读，中文不会乱码。
#   3. 文件还不存在就**先建空文件**（服务启动需要几十秒）：否则 Get-Content -Wait
#      会直接报错退出，而这恰恰是最需要看日志的那段时间。

param(
    [Parameter(Mandatory = $true)][string]$LogDir,
    [int]$Tail = 15
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8

# 顺序 = 打印顺序（网关放最前：503 这类问题先看它）
$logs = [ordered]@{
    'gateway' = @{ file = 'gateway-nacos-sentinel.log'; color = 'Cyan' }
    'user'    = @{ file = 'user-service.log';           color = 'Green' }
    'content' = @{ file = 'content-service.log';        color = 'Magenta' }
    'ai'      = @{ file = 'ai-service.log';             color = 'Yellow' }
    'python'  = @{ file = 'ai_python.log';              color = 'Gray' }
}

if (-not (Test-Path -LiteralPath $LogDir)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
}
foreach ($entry in $logs.Values) {
    $path = Join-Path $LogDir $entry.file
    if (-not (Test-Path -LiteralPath $path)) {
        New-Item -ItemType File -Force -Path $path | Out-Null
    }
}

$jobs = foreach ($name in $logs.Keys) {
    $path = Join-Path $LogDir $logs[$name].file
    Start-Job -Name $name -ScriptBlock {
        param($p, $n, $t)
        Get-Content -LiteralPath $p -Encoding UTF8 -Tail $t -Wait |
            ForEach-Object { "[$n] $_" }
    } -ArgumentList $path, $name, $Tail
}

Write-Host ''
Write-Host '==== following all 5 logs in this window ====' -ForegroundColor White
Write-Host '  prefix colors: gateway=cyan user=green content=magenta ai=yellow python=gray' -ForegroundColor DarkGray
Write-Host '  Ctrl+C stops FOLLOWING only - the services keep running.' -ForegroundColor DarkGray
Write-Host '  stop everything: stop-all.bat' -ForegroundColor DarkGray
Write-Host ''

try {
    while ($true) {
        foreach ($job in $jobs) {
            $lines = Receive-Job -Job $job -ErrorAction SilentlyContinue
            if ($lines) {
                $color = $logs[$job.Name].color
                foreach ($line in $lines) {
                    Write-Host $line -ForegroundColor $color
                }
            }
        }
        Start-Sleep -Milliseconds 250
    }
}
finally {
    # Ctrl+C / 终端关闭：把后台 job 收干净，别留下隐藏的 powershell 子进程
    foreach ($job in $jobs) {
        Stop-Job -Job $job -ErrorAction SilentlyContinue
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    Write-Host ''
    Write-Host 'stopped following logs (services are still running)' -ForegroundColor DarkGray
}
