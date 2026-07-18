# Qdrant 关闭 API Key 执行计划

## 目标

关闭 `RAG-Server` 上 Qdrant 的 API Key 认证，使通过 SSH 隧道访问 Dashboard 和 REST API 时无需填写密钥，同时保持服务仅监听服务器回环地址，不新增公网暴露面。

## 变更边界

- 修改本地及服务器 Compose 配置，移除 Qdrant admin/read-only API Key 环境变量。
- 修改 Prometheus 抓取配置和挂载，移除不再需要的认证头与只读密钥挂载。
- 保留 Qdrant 数据目录、快照目录、资源限制、健康检查、日志轮转、UFW 与回环端口绑定。
- 不上传或部署 Java、Vue、Python 业务项目，不修改 Qdrant 业务数据。

## 执行计划

1. 读取 `codex.md`、现有运行配置和服务器实时状态，确认当前认证配置及网络边界。
2. 备份服务器运行配置，修改本地无密钥配置并完成 YAML/Compose 静态校验。
3. 同步基础设施配置至服务器，仅重建受影响的 Qdrant 与 Prometheus 容器。
4. 验证匿名 collection API、Dashboard、Prometheus 抓取目标、端口监听和持久化数据不受影响。
5. 重启容器再次验证匿名访问与自动恢复，更新 `codex.md` 和本计划的执行记录。
6. 检查差异，只提交本轮相关文件，使用中文提交信息。

## 回滚方案

- 服务器修改前保存带时间戳的 Compose、Prometheus 与 `.env` 配置副本。
- 若匿名模式启动或监控验证失败，恢复备份配置并重建 Qdrant/Prometheus。
- 回滚不得删除 volume、Qdrant storage、snapshot 或 collection 数据。

## 执行记录

### 2026-07-18：关闭认证并完成验收

- 执行前确认 Qdrant、Prometheus、Node Exporter 均正常运行；Qdrant `6333/6334` 与 Prometheus `9090` 只监听 `127.0.0.1`，UFW 入站仍只放行 SSH。
- 修改 Compose，移除 `QDRANT__SERVICE__API_KEY`、`QDRANT__SERVICE__READ_ONLY_API_KEY` 和 Prometheus 只读密钥挂载；修改 Prometheus 配置，移除 Bearer 认证头；`.env.example` 改为无凭据说明。
- 本地 Compose/Prometheus YAML 解析通过，认证变量和认证头引用扫描为空；远端 `docker compose config --quiet` 与 `promtool check config` 均通过后才替换运行配置。
- 服务器旧 Compose、Prometheus 和 `.env` 已备份至 `/opt/ai-agent-rag/backups/qdrant-auth-20260718-114442`，目录权限为 `700`；失败回滚脚本不会删除 Qdrant storage、snapshot 或 collection。
- 仅上传三份中间件配置，没有上传 Java、Vue、Python 业务项目；只重建 Qdrant 与 Prometheus，Node Exporter 未重建。
- 匿名访问 `/collections`、Dashboard、创建临时 collection、重启后读取临时 collection、删除临时 collection 均返回 HTTP 200，测试 collection 已清理。
- 重启后 Qdrant 与 Prometheus 均为 healthy；Prometheus 查询得到 `prometheus=1`、`qdrant=1`、`node-exporter=1`。
- 容器实际环境中不存在 Qdrant API Key 变量，Prometheus 不再挂载只读密钥；活动只读密钥文件已删除，运行时 `.env` 不含凭据。
- 再次确认 `6333/6334/9090` 只绑定回环地址；从本机探测服务器公网 IP 的三个端口均不可达，没有因关闭认证扩大公网暴露面。
- 更新本地 `codex.md` 的 Qdrant 认证状态、目录说明与访问边界；该文件按既有规则仅供本地使用，不纳入 Git。
