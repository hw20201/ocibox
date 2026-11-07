# OCIBox 安全升级说明（fork: hw20201/ocibox，分支 security-fixes）

基于 2026-11-07 的代码审查结论修复。只改安全问题，不动业务逻辑、不动 OCI 指纹与代理校验。

## 修了什么

### B1 — 管理员密码不再明文存盘
- `install.sh` / `update.sh`：删除 `save_admin_password_plain` 与 `resolve_admin_password`（含内嵌 CLI 里的副本），删除 `application.yml.bak.password` 密码副本逻辑（改用带时间戳的旧配置备份恢复）。安装结束时仍从内存变量打印一次密码，之后只留 PBKDF2 哈希。
- `AccountYamlService.saveRememberedAdminPassword()` 已删除，改为 `deleteRememberedAdminPassword()`；`AdminAuthService.upgradeYamlPassword()` 不再先存明文再哈希，启动时顺手清理历史遗留的 `.admin-password` 文件。
- 注意：旧部署磁盘上已有的 `.admin-password` 与 `application.yml.bak.password`，升级后首次启动会被自动清理（前者），后者请手动删除。

### B2 — 实例 root 密码 / SSH 密钥不再进库、不再发 Telegram
- `CreationTaskRecord.password` / `sshKey` 改为 `@Transient`：只在内存里供抢机线程使用，绝不写入 SQLite。新增持久化标志 `hadCredentials`，记录任务是否带凭据。
- `SqliteSchemaAutoMigrator`：新库不再建 `password` / `ssh_key` 列；旧库升级时先把含凭据的旧任务标为 `had_credentials=1`，再清空这两列（幂等）。
- 重启恢复（`CreationTaskService.resumeTasksOnStartup`）：含凭据的旧任务不再静默用无凭据配置去抢机，直接标记 FAILED 并提示重新创建。
- Telegram 抢机成功通知、`instance-reinstall` 的开始/成功监控通知：不再含 root 密码，只写登录方式。
- `POST /api/admin/instance-reinstall`：响应不再回显 `generatedPwd`（密码是你提交的，你本来就知道）；前端不读该字段，改动安全。
- 抢机重试间隔加下限 10 秒（`interval <= 0` 仍默认 30 秒）。

### B3 — 删除 `curl | bash` 兜底
- `install.sh` / `update.sh` 的 `ocibox_update()`：本地 `update.sh` 缺失时直接报错退出，提示重跑 `install.sh`，永不从网络拉取执行脚本。

### B4 — Shell 自更新校验改为 fail-closed
- `install.sh` / `update.sh`：拿不到官方 64 位十六进制 SHA、或 SHA 与下载包不符、或本机缺少 `sha256sum`，一律中止并保留原程序。摘要只认与本次实际下载文件同名的那一行；删掉了永远不可能成功的 `${USED_URL}.sha256` 兜底（构建只发 `checksums.txt`）。

### B5 — 默认只绑 127.0.0.1
- `install.sh` / `update.sh`：解绑域名时不再把 `address` 改回 `0.0.0.0`，保持 `127.0.0.1`；`README.md` 手动部署示例同步改为 `127.0.0.1`，并注明远程访问走 Nginx 反代 + TLS。解绑后如需远程访问，请用 SSH 端口转发或重新绑定域名。

### 纵深防御（顺手补的便宜项）
- `AdminAuthInterceptor`：对 POST/PUT/DELETE/PATCH 加同源检查（Origin/Referer 的 host 须与 Host 一致；不带 Origin/Referer 的非浏览器调用放行，Token 鉴权仍在）。只比 host 不比 scheme，反代终止 TLS 时不误杀。
- `application.yml`：`server.error.include-stacktrace/message/binding-errors` 全部设为 `never`。
- Telegram 机器人：换 IP、关机、重启改为两步确认（点一次弹确认页，点确认才执行）；开机不拦截。

## 刻意保留的例外（有理由，不是漏改）

1. **Telegram 机器人重装的初始密码仍经 Telegram 发送一次**（`TelegramBotService.handleReinstallExecute`，现包在 `<tg-spoiler>` 里）：该密码由服务端随机生成，且 Telegram 是该流程唯一的送达通道（重装时未提供 SSH 密钥），删掉它会造出一台谁也登不上的机器。替代方案是改机器人流程让管理员自己提供密码，属于功能 redesign，未做。
2. **生成 SSH 密钥接口仍一次性返回私钥**（`POST /api/admin/creation/generate-key` 的 `privateKey`）：密钥在内存里现生成、现返回、从不入库，前端直接下载成文件；删掉它等于删掉“生成密钥”功能。返回通道本身就是一次性展示。

## 明确没动的东西

- OCI 客户端指纹 / 代理 SSRF 校验（`OciClientWrapper`）一字未动；LAN 代理仍允许、校验失败仍 fail-open（已知设计取舍，见审查报告）。
- Java 自更新的 fail-closed 校验逻辑未动。
- 实例 cloud-init 强制 `PermitRootLogin yes` + 密码认证 + 清空宿主防火墙未动（上游设计如此，接受该风险；用强随机密码 + 及时改密是正解）。
- PBKDF2 保持 210k 迭代（低于 OWASP 现行 600k 建议，但属旧标准值，未动）。
- 未做 WebAuthn 重写、未做抢机重试 redesign、未改任何 UI。

## 升级后必读

- 旧任务：含 root 密码 / SSH 密钥的抢机任务在服务重启后会自动停止，请重新创建。
- 旧密码文件：首次启动自动清理 `.admin-password`；`application.yml.bak.password` 如还在请手动删。
- 解绑域名后面板只监听本机，远程访问请用 SSH 端口转发（`ssh -L 5000:127.0.0.1:5000`）或重新绑定域名。
