# RAG 服务器中间件部署计划

## 目标

在不上传或部署本地业务项目的前提下，为 `RAG-Server` 建立首期 RAG 运行基座，部署并验证容器运行时、Qdrant 与基础监控组件，随后据实更新 `codex.md`。

## 本轮边界

- 部署：Docker Engine、Docker Compose Plugin、Qdrant、Prometheus、Node Exporter。
- 准备：固定目录、持久化卷、日志轮转、资源上限、健康检查、自动重启和备份目录。
- 不部署：本地 Java/Vue 项目、`rag-api`、`rag-worker`、Embedding/Reranker 模型、Docling/PaddleOCR 业务镜像。
- 不迁移：现有 MySQL、Kafka、MinIO、Nacos、XXL-JOB、Grafana。
- 不开放：Qdrant gRPC、Prometheus、Node Exporter 的公网访问；如确需应用服务器访问 Qdrant，仅按来源 IP 放行 HTTP 端口。

## 执行计划

1. 只读确认重装后的系统版本、CPU/内存/磁盘、网络、防火墙、SSH 和已有软件，确认不存在需要保留的同名服务或数据目录。
2. 安装并启用 Docker Engine 与 Docker Compose Plugin，配置日志轮转和开机启动，验证容器运行。
3. 在服务器 `/opt/ai-agent-rag` 建立独立 Compose 基座与数据目录，固定镜像版本并配置安全选项、健康检查、资源限制和持久化。
4. 启动 Qdrant、Prometheus、Node Exporter；验证容器健康、端口监听、Qdrant collection API、重启恢复和资源占用。
5. 检查公网暴露面，记录实际版本、目录、端口、运维命令、备份边界和后续模型服务接入点。
6. 将实际操作和验证结果追加到本文，更新 `codex.md`，只提交本轮文档改动并使用中文提交信息。

## 回滚原则

- Compose 配置按时间备份；升级前保留上一版镜像标签与 Qdrant snapshot。
- 停止服务使用 `docker compose stop`，禁止在无明确确认时执行 `down -v` 或删除数据目录。
- 若安装或启动验证失败，保留诊断信息并停止对外开放，不伪装为部署完成。

## 执行记录

### 2026-07-18：SSH 重装后身份校验

- 新服务器返回 OpenSSH `9.6p1 Ubuntu-3ubuntu13.5`，确认系统已重装为 Ubuntu 系列。
- 重装导致服务器 ED25519 主机指纹变化；通过独立 `ssh-keyscan` 核对新指纹后，仅替换本机 `known_hosts` 中该 IP 的旧记录，没有关闭主机校验。
- `codex.md` 记录的原 root 密码已无法登录；服务器当前只开放 password 认证，本机已有 SSH key、`dadaikuai` key 以及常见 `ubuntu/rocky` 用户配合同一旧密码均未通过。
- 用户再次确认相同 IP 和密码后，重新验证 `root/ubuntu/admin/debian/centos/cloud-user/ec2-user`，服务器仍全部拒绝；该结论来自真实 SSH 认证结果，不再继续扩大账户或口令尝试范围。
- 用户进一步确认登录用户名就是 `root`；再次失败表明阻塞点位于重装后的 root 密码状态或 SSH 的 `PermitRootLogin` 策略，需要通过云厂商控制台重置/启用，或向 `/root/.ssh/authorized_keys` 注入本机公钥。
- 已从官方发布记录锁定待部署候选版本：Qdrant `v1.18.2`、Prometheus `v3.12.0`、Node Exporter `v1.11.1`；实际部署前仍会校验对应容器镜像 digest。
- 尚未进入服务器，因此本轮未安装 Docker、未创建服务器目录、未启动任何容器，也未改动远端配置。等待取得重装后的有效 SSH 用户和密码后继续执行本计划。
