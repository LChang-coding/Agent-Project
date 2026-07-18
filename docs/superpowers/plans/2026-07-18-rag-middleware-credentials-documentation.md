# RAG 中间件认证信息文档计划

## 目标

核对 `RAG-Server` 当前已部署中间件的实际认证配置，并将账号、密码、访问地址和网络边界明确更新到本地 `codex.md`。

## 执行计划

1. 只读检查远端全部容器、Compose 环境变量、Prometheus 配置、端口监听和防火墙规则。
2. 区分“存在账号密码”“明确无认证”“仅服务器或 Docker 内网访问”，避免将无认证误认为遗漏凭据。
3. 更新 `codex.md` 的 RAG 中间件认证表，不创建或轮换任何账号密码。
4. 追加本计划的实际执行记录，复核敏感信息没有进入 Git 暂存区。
5. 只提交本计划文档，使用中文提交信息；`codex.md` 继续按本地排除规则管理。

## 执行记录

### 2026-07-18：认证状态核对与文档更新

- 远端 `docker ps -a` 确认当前只有 Qdrant、Prometheus、Node Exporter 三个容器，没有遗漏其他已部署中间件。
- 检查 Qdrant 容器实际环境变量，不存在 admin/read-only API Key 配置；Qdrant 当前无账号、密码或 API Key。
- 检查 Prometheus 运行配置与 Compose，不存在 `authorization`、Basic Auth、Bearer、OAuth 或 Web 配置认证。
- 检查 Node Exporter 启动参数，不存在 Web 认证或 TLS 认证配置。
- 核对端口和 UFW：SSH `22` 与 Qdrant HTTP `6333` 公网开放；Qdrant gRPC `6334`、Prometheus `9090` 仅回环可达；Node Exporter 未发布宿主机端口。
- 在本地 `codex.md` 新增“RAG 服务器认证信息”表，明确 SSH 使用 root 密码认证，其余已部署中间件均为“无认证”，并记录各自访问边界。
- 没有创建、修改或轮换任何账号密码，没有改动服务器配置和业务数据。
- `codex.md` 由 Git 本地排除规则管理，真实 SSH 密码未进入本计划或 Git 暂存区。
