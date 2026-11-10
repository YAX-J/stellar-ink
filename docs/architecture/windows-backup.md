# 用 Windows 电脑保存生产备份

生产机每天生成 age 加密包，Windows 主动通过 SSH 拉取；电脑不需要公网 IP 或开放入站端口。
用户当前没有生产环境，本文保留为后续部署操作手册，历史服务器地址不可视为当前可用地址。
生产机只放公钥，恢复私钥始终留在电脑。只有电脑完成 SHA-256 与 age 全包认证解密，并回执成功后，
生产监控才记录异地成功；单纯生成服务器本地文件不算异地成功。

## 1. Windows 准备

需要 Python 3.11+、OpenSSH 客户端、age 1.2.1+。在 PowerShell 安装 age：

```powershell
winget install --id FiloSottile.age -e
```

安装后重新打开 PowerShell，确认 `age --version`、`age-keygen --version`、`ssh -V` 可用。
安装方式见 [age 官方说明](https://github.com/FiloSottile/age#installation)。

在仓库根目录初始化（仅首次执行）：

```powershell
python deploy/scripts/pull-backup.py init
```

默认目录为仓库根的 `.backups`。可以在初始化及后续拉取时统一在子命令前加
`--directory D:\你选定的备份目录`；本次电脑已在 `D:\myproject\stellar-ink\.backups` 初始化，无需重复执行。
目录 ACL 限定当前 Windows 用户；已有目录会拒绝初始化，防止覆盖唯一私钥。
`recovery-key.txt` 是私钥，须另存一份到安全位置；电脑损坏且没有私钥副本时，加密备份也无法恢复。
`.backups`、私钥和密文均已排除 Git／Docker 构建，不能随清理仓库、换电脑等操作直接删除。

## 2. 只上传公钥

先核对生产机 SSH 主机指纹并完成一次手工登录。下面地址来自现有生产环境；SSH 端口非22时，
`ssh` 增加 `-p 端口`，`scp` 增加 `-P 端口`。

```powershell
ssh root@103.14.33.78 "mkdir -p /etc/stellar-ink"
scp .backups/backup-recipients.txt root@103.14.33.78:/etc/stellar-ink/backup-recipients.txt
```

只上传 `backup-recipients.txt`，不上传 `recovery-key.txt`。
正式拉取使用 `BatchMode=yes` 和 `StrictHostKeyChecking=yes`，需要可用的 SSH 密钥认证或已解锁的 ssh-agent。
不能用保存密码、关闭主机校验的方式凑通。先验证：

```powershell
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes root@103.14.33.78 "echo SSH_OK"
```

如果失败，先配置该电脑到生产机的 SSH 密钥登录；SSH 登录私钥和 age 恢复私钥是两个用途不同的文件。

## 3. 生产机配置

先将本次代码同步到生产机。Linux 仍需 age、Python 3.11+、flock、tar、gzip、sha256sum、Docker Compose；
pull 模式不需要 rsync。不要改用 `BACKUP_ALLOW_LOCAL_ONLY=1` 冒充异地成功。

```bash
cd /app/stellar-ink
sudo mkdir -p /etc/stellar-ink
test -f /etc/stellar-ink/backup.env || sudo install -m 600 deploy/scripts/backup.env.example /etc/stellar-ink/backup.env
sudo nano /etc/stellar-ink/backup.env
```

在文件中配置（保留已有宿主机数据库配置等需要的字段，不要盲目覆盖已有文件）：

```dotenv
BACKUP_ROOT=/data/stellar-ink/backups
BACKUP_RECIPIENTS_FILE=/etc/stellar-ink/backup-recipients.txt
BACKUP_DELIVERY_MODE=pull
BACKUP_REMOTE=
BACKUP_KEEP_DAYS=7
```

`BACKUP_KEEP_DAYS` 是服务器暂存保留天数，电脑离线超过此窗口可能错过旧备份。

```bash
sudo install -m 644 deploy/systemd/stellar-ink-backup.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now stellar-ink-backup.timer
sudo systemctl start stellar-ink-backup.service
sudo journalctl -u stellar-ink-backup.service -n 40
```

默认上海时间每天03:00生成备份。电脑暂未下载时，“从未成功／超过26小时未成功”的异地告警是预期行为，
不能通过修改指标或关闭规则来消除它。

## 4. Windows 手动拉取并验收

```powershell
python deploy/scripts/pull-backup.py pull --host root@103.14.33.78
```

需要指定 SSH 登录私钥时添加 `--ssh-identity C:\你的路径\ssh私钥`，非默认端口添加 `--port 端口`。
脚本下载最新完整包到临时文件，核对大小、SHA-256，再用本机私钥验证整个 age 密文可认证解密，明文不写盘。
校验成功后落盘 `.tar.age` 与 `.sha256` 并发送回执；传输中断或解密失败会清理临时包，不发送回执。
同名不同内容文件拒绝覆盖；回执失败时已完成的本地包会保留，再次运行可重试。

每次只拉取最新包，不补拉全部历史。本机暂不自动删除旧包，需关注剩余空间；自动化前先完成一次
[独立环境恢复演练](production-p0.md#4-恢复演练)，校验密文可解密不等于已经完成数据库恢复。
异地成功时间按备份生成时间记录，下载过期旧包不会把它伪装成今日新备份。

## 5. Windows 每日拉取

手工成功后注册09:00（电脑本地时间）任务：

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/register-backup-pull.ps1 -Server root@103.14.33.78
```

如果 Python／age 不在 PATH，可用 `-Python D:\miniconda\python.exe`、`-Age C:\实际路径\age.exe` 指定，
SSH 私钥用 `-SshIdentity`，端口用 `-Port`。任务使用当前用户交互登录会话，不保存 Windows 密码；
电脑关机或未登录时无法执行，开启“可用时补跑”，不同实例不会并行运行。
脚本拒绝覆盖同名任务；需要调整时在任务计划程序中编辑已有任务。
可在任务计划程序中手工运行，并检查最后运行结果为0、备份文件有更新、生产异地成功指标已推进。

本机回归：`python deploy/scripts/test-backup-pull.py`。其中真实加密项需要 `age`／`age-keygen` 在 PATH；
其余覆盖中断清理、篡改拒绝、回执失败重试及已有备份保护。生产 SSH、定时执行和真实库恢复仍须现场验收。
