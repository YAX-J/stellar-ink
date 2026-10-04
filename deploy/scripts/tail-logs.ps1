# Stellar Ink - follow every service log in ONE console (Windows)
#
# ⚠️ THIS FILE MUST STAY "UTF-8 WITH BOM". Do not let an editor rewrite it without
#    the BOM: Windows PowerShell 5.1 reads a BOM-less UTF-8 .ps1 as ANSI (GBK on
#    this machine), and the mis-decoded Chinese comments then swallow line breaks
#    - the parser reports a bogus "unexpected }" and the script refuses to run.
#    Measured: same bytes without BOM -> parse error at line 75; with BOM -> fine.
#
# 用途：start-all.bat 在 SHOW_LOGS=1 模式下结束时调用它，把 4 个 java 服务 +
# python 的日志**汇总到同一个终端**，每行带服务前缀与颜色。
#
# 为什么不是「让服务直接往当前终端写」：cmd 没有 tee，多个后台进程共享同一个
# stdout 会变成**无前缀的交错文本**（5 个 JVM 同时启动时基本没法读）。所以走
# 「服务写文件 + 这里 tail」这条路：既有前缀，又不丢文件日志。
#
# ⚠️ **按目录发现文件，不写死文件名**（这是踩过的一课）：
#    第一版把 java 日志名写成 `user-service.log`（按 artifactId 猜），而 logback 里
#    `APP_NAME` 是硬编码的 **`user_service`**（下划线）—— 于是它**建了 4 个空文件**
#    在那儿盯着，真实日志在别的文件里，表现就是「跟随窗口一片空白，看起来服务没起来」，
#    而服务其实全是 UP。现在改成：扫 logs\*_service.log（排除滚动文件）+ ai_python.log，
#    并且**等文件出现再挂上去**，所以文件名再变也不会静默失联。
#
# 三条约定：
#   1. **Ctrl+C 只停止跟随，不停服务** —— 服务是 start 起的独立进程；
#      要停服务请用 stop-all.bat。
#   2. 文件按**行**读（logback 立即刷盘、python 端设了 PYTHONUNBUFFERED），
#      所以看到的就是实时的；用 -Encoding UTF8 读，中文不会乱码。
#   3. 文件还没出现就**等着**（服务启动要几十秒，那正是最需要看日志的时候），
#      但**绝不预先创建文件** —— 造出空文件只会让「跟随成功」变成假象。

param(
    [Parameter(Mandatory = $true)][string]$LogDir,
    [int]$Tail = 15
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8

# 固定颜色（前缀 -> 颜色）；没列到的服务按后面的调色板轮着来，
# 这样将来加服务也能自动被跟随，不需要改这个脚本。
$knownColors = @{
    'gateway' = 'Cyan'
    'user'    = 'Green'
    'content' = 'Magenta'
    'ai'      = 'Yellow'
    'python'  = 'Gray'
}
$palette = @('White', 'DarkCyan', 'DarkGreen', 'DarkMagenta', 'DarkYellow', 'Blue')
$paletteIndex = 0

function Get-LogPrefix([string]$fileName) {
    $name = $fileName -replace '\.log$', ''
    if ($name -eq 'ai_python') { return 'python' }
    if ($name -match '_service$') { return ($name -replace '_service$', '') }
    return $name
}

function Get-LogColor([string]$prefix) {
    if ($knownColors.ContainsKey($prefix)) { return $knownColors[$prefix] }
    $script:paletteIndex = ($script:paletteIndex + 1) % $palette.Count
    return $palette[$script:paletteIndex]
}

# 滚动归档（app.2026-09-25.0.log）不算「当前日志」：跟它会看到去年的内容
$rolledPattern = '\d{4}-\d{2}-\d{2}\.\d+\.log$'

function Find-CurrentLogs([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return @() }
    Get-ChildItem -LiteralPath $dir -File -Filter '*.log' |
        Where-Object { $_.Name -notmatch $rolledPattern } |
        Where-Object { $_.Name -eq 'ai_python.log' -or $_.Name -match '_service\.log$' } |
        Sort-Object Name |
        Select-Object -ExpandProperty FullName
}

if (-not (Test-Path -LiteralPath $LogDir)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
}

Write-Host ''
Write-Host '==== following the service logs in this window ====' -ForegroundColor White
Write-Host '  java: logs\*_service.log   +   python: logs\ai_python.log' -ForegroundColor DarkGray
Write-Host '  Ctrl+C stops FOLLOWING only - the services keep running.' -ForegroundColor DarkGray
Write-Host '  stop everything: stop-all.bat' -ForegroundColor DarkGray
Write-Host ''

$jobs = @{}   # 完整路径 -> job
try {
    while ($true) {
        # 1) 发现新出现的日志文件并挂上跟随（服务启动期间会陆续出现）
        foreach ($path in Find-CurrentLogs $LogDir) {
            if ($jobs.ContainsKey($path)) { continue }
            $prefix = Get-LogPrefix (Split-Path $path -Leaf)
            $jobs[$path] = Start-Job -Name $prefix -ScriptBlock {
                param($p, $n, $t)
                Get-Content -LiteralPath $p -Encoding UTF8 -Tail $t -Wait |
                    ForEach-Object { "[$n] $_" }
            } -ArgumentList $path, $prefix, $Tail
            Write-Host "  (attached to $(Split-Path $path -Leaf))" -ForegroundColor DarkGray
        }

        # 2) 把每个 job 的新行打出来
        foreach ($path in @($jobs.Keys)) {
            $job = $jobs[$path]
            $lines = Receive-Job -Job $job -ErrorAction SilentlyContinue
            if ($lines) {
                $color = Get-LogColor $job.Name
                foreach ($line in $lines) { Write-Host $line -ForegroundColor $color }
            }
        }
        Start-Sleep -Milliseconds 250
    }
}
finally {
    # Ctrl+C / 终端关闭：把后台 job 收干净，别留下隐藏的 powershell 子进程
    foreach ($job in $jobs.Values) {
        Stop-Job -Job $job -ErrorAction SilentlyContinue
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    Write-Host ''
    Write-Host 'stopped following logs (services are still running)' -ForegroundColor DarkGray
}
