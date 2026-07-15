# XXL-JOB 3.4.0 部署

该部署单元只负责分布式唤醒。`agent_schedule_config/task/execution` 仍是业务调度唯一真源，XXL-JOB Admin 使用独立 `xxl_job` MySQL 数据卷。

## 部署

1. 先备份业务库，并按时间顺序幂等应用 `2026-07-11-context-manager.sql`、`2026-07-14-chat-run-control.sql`、`2026-07-15-chat-session-share.sql`、`2026-07-15-distributed-scheduler.sql`。至少确认 `chat_session.context_revision`、`chat_run` 和三张 `agent_schedule_*` 表存在，再允许 `agent_prompt` 执行。
2. 复制 `.env.example` 为 `.env`，为四个必填项设置不同的高熵值；其中 Admin 密码必须为 4~20 个字符，因为 XXL-JOB 3.4.0 登录页会截断超过 20 个字符的输入；`.env` 不得提交。
3. 执行 `./deploy.sh`。脚本先启动独立 MySQL，再下载并校验 XXL-JOB `v3.4.0` 官方初始化 SQL，删除官方示例任务，幂等写入两个默认停用的业务唤醒任务，最后启动 Admin 并做 HTTP 健康检查。
4. 当前 Admin 监听 `0.0.0.0:8080`，可通过 `http://<服务器公网 IP>:8080/xxl-job-admin` 直接访问；管理端口暴露公网且没有 TLS，只适合作为当前简化方案，需保持高强度管理密码。长期使用建议改回 `127.0.0.1`，通过 Nginx/TLS 和访问控制发布。数据库端口始终禁止直接暴露。
5. 应用容器/进程配置：

```text
XXL_JOB_EXECUTOR_ENABLED=true
XXL_JOB_ADMIN_ADDRESSES=http://<Admin 可达地址>:8080/xxl-job-admin
XXL_JOB_ACCESS_TOKEN=<与 .env 相同>
XXL_JOB_EXECUTOR_APPNAME=ai-agent-scheduler
XXL_JOB_EXECUTOR_IP=<Admin 可回调的应用地址>
XXL_JOB_EXECUTOR_PORT=9999
AI_SCHEDULER_LOCAL_FALLBACK_ENABLED=false
```

服务器防火墙只允许 Admin 到执行器 `9999/tcp`，执行器不面向公网开放。应用多实例使用相同 appName 自动注册；业务数据库租约和 fencing 再保证同一运行态只有一个有效提交者。

## 初始化任务

| Handler | Admin Cron | 作用 |
|---|---|---|
| `scheduleReconcileJobHandler` | `0 */5 * * * ?` | 长间隔扫描配置并 hash upsert 运行态 |
| `scheduleDispatchJobHandler` | `*/5 * * * * ?` | 短间隔抢占到期运行态并执行 |

`bootstrap-business-jobs.sql` 会按 appName + handler 幂等创建/更新，不因重复部署产生第二份唤醒任务。
首次部署时两项任务保持停用；确认应用执行器已经自动注册且 Admin 能回调 `9999/tcp` 后，再从 Admin 页面“任务管理”启用，避免空执行器期间持续产生失败日志。两项任务的初始 Cron 和默认停用状态位于 `bootstrap-business-jobs.sql`：配置对账每五分钟执行一次，任务派发每五秒执行一次。

## 上线与回滚

- 上线顺序：备份业务调度表 → 执行业务迁移 SQL → 部署 Admin → 启动应用执行器 → 在 Admin 检查注册地址和两项调度日志 → 前端创建一项测试配置。
- 暂停调度：先在 Admin 停止两个唤醒任务，再设置应用 `XXL_JOB_EXECUTOR_ENABLED=false`；不要删除业务调度表。
- 组件回滚：`docker compose --env-file .env down` 保留命名卷；恢复时重新执行 `deploy.sh`。
- 业务迁移回滚不建议直接删列；先停唤醒和配置创建，使用备份恢复三张表，再回滚应用版本。

官方初始化脚本来源固定为 `https://raw.githubusercontent.com/xuxueli/xxl-job/v3.4.0/doc/db/tables_xxl_job.sql`，SHA-256 固定为 `946bb73716e3ae9fd1c2d9b5083e8d28c84c3b9e0f11b44a31b1b82bb52f9cba`。
