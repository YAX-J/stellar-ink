# 注册当前 Windows 用户登录期间运行的每日下载任务；电脑关机时无法备份，登录后补跑。
param(
    [Parameter(Mandatory=$true)][string]$Server,
    [string]$At = '09:00',
    [string]$Age = 'age',
    [string]$Python = 'python',
    [string]$SshIdentity = '',
    [int]$Port = 22
)
$ErrorActionPreference = 'Stop'
if ($Server -notmatch '^[A-Za-z0-9_][A-Za-z0-9_.@-]*$' -or $Port -lt 1 -or $Port -gt 65535) { throw '服务器地址或端口不合法' }
$repoDirectory = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$pythonExecutable = (Get-Command $Python -CommandType Application).Source
$ageExecutable = (Get-Command $Age -CommandType Application).Source
$arguments = @((Join-Path $PSScriptRoot 'pull-backup.py'), 'pull', '--host', $Server, '--port', "$Port", '--age', $ageExecutable)
if ($SshIdentity) { $arguments += @('--ssh-identity', (Resolve-Path -LiteralPath $SshIdentity).Path) }
# 任务计划程序使用单条参数字符串；不接受引号和换行，防止路径改变命令含义。
$argumentText = ($arguments | ForEach-Object {
    if ($_ -match '["\r\n]') { throw '任务参数不能包含引号或换行' }
    '"' + $_ + '"'
}) -join ' '
$action = New-ScheduledTaskAction -Execute $pythonExecutable -Argument $argumentText -WorkingDirectory $repoDirectory
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2)
$principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'StellarInkBackupPull' -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
Write-Output '每日拉取任务已注册；请先手工拉取成功，定期检查最后运行结果和本地备份文件。'
