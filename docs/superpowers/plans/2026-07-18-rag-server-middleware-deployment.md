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

### 2026-07-18：取得新凭据后恢复执行

- 用户提供重装后的新 root 密码；先重新验证 SSH，再从计划第 1 步继续。
- 本阶段仍严格限定为 RAG 中间件基座，不上传本地 Java/Vue/Python 业务项目。
- 服务器随后再次更换 ED25519 主机指纹；已使用独立扫描核对新指纹后更新本机记录，没有关闭主机校验。当前 SSH 在 `kex_exchange_identification` 阶段由远端关闭，尚未进入密码认证。
- 新增无密钥的可复现运行配置 `docs/dev-ops/rag-runtime/`，固定 Qdrant/Prometheus/Node Exporter 镜像版本，包含持久化、回环端口、资源限制、健康检查、日志轮转与 Prometheus 抓取配置；YAML 语法校验通过，Compose 语义校验待远端 Docker 安装后执行。

### 2026-07-18：第二次更换系统后恢复执行

- 用户明确服务器再次更换系统并提供最新 root 密码；废弃上一系统的指纹、密码和所有未验证状态。
- 重新从主机指纹核验开始，登录成功后重新执行完整系统审计，不假定 Docker 或任何 RAG 中间件存在。
- 登录审计确认实际系统仍为 CentOS 7.9、内核 3.10、16 核、15 GiB RAM、40 GiB XFS、无 Swap、无 Docker；开始安装 Docker 前置依赖时旧 CentOS 镜像源出现 404，执行会话随后超时中断，必须先检查 rpm/yum 事务状态。

### 2026-07-18：第三组 root 密码

- 用户更新 root 密码；恢复连接后先审计上次中断产生的部分状态和包管理锁，再决定修复源与继续安装，避免重复或半安装。
- 随后远端 SSH 软件标识变为 `OpenSSH_8.9p1 Ubuntu-3`，证明服务器已经从 CentOS 7 切换为 Ubuntu；旧系统上的 yum 事务与仓库修改不再属于当前服务器状态。
- Ubuntu 初始化期间 SSH 在密钥交换阶段主动关闭，暂未返回稳定的新主机公钥；继续采用低频探测，待能够独立读取指纹后再更新 `known_hosts` 和登录。

### 2026-07-18：中间件部署与验收完成

- Ubuntu 初始化稳定后独立扫描并核验 ED25519 指纹，更新本机 `known_hosts`；使用最新 root 密码登录成功，确认 Ubuntu 22.04 LTS、16 核、15 GiB RAM、39 GiB ext4 根盘。
- 从 Docker 官方 APT 仓库安装 Docker Engine/CLI `29.6.2`、Docker Compose `5.3.1`、containerd `2.2.6`，启用开机启动；Docker daemon 配置通过校验，使用 overlay2、systemd cgroup、live-restore 和全局日志轮转。
- 创建并启用 2 GiB `/swapfile` systemd swap unit，整机重启后仍自动挂载。
- 部署 Qdrant `1.18.2`、Prometheus `3.12.0-distroless`、Node Exporter `1.11.1`；镜像均固定版本并记录 digest，配置包含非 root UID、cap drop、no-new-privileges、资源/PID 上限、健康检查、持久化目录和日志轮转。
- 镜像 digest 分别为 Qdrant `sha256:75eab8c4...c9e50c`、Prometheus `sha256:f39df533...a1e3c2`、Node Exporter `sha256:0f422f62...9df241`；本地四份运行配置与服务器实际文件 SHA-256 完全一致。
- Qdrant 启用 admin/read-only API Key、关闭遥测；密钥仅保存在远端权限 `600/400` 文件。首次生成的密钥因调试 shell 回显被立即判定泄漏，在容器启动前完成轮换，泄漏值从未投入使用。
- Prometheus 使用独立只读密钥采集 Qdrant，验证 `prometheus/qdrant/node-exporter` 三个 target 均为 `up`；修复了最初 `/metrics` 返回 401 的监控缺口。
- 功能验证：Qdrant 未认证请求返回 401；认证后创建、读取和删除测试 collection 成功；写入测试 point 后重启 Compose，payload 仍可读取，证明持久化有效，随后清理测试数据。
- 安装并启用 UFW，默认拒绝入站、仅开放 SSH 22；Qdrant HTTP/gRPC 与 Prometheus 均绑定 `127.0.0.1`，Node Exporter 不发布宿主机端口。外部协议级探测没有获得中间件响应。
- 完成 Ubuntu 全量更新：314 个包，其中 201 个安全更新；保留云厂商定制 `cloud.cfg` 修复非交互配置，最终待更新包为 0、`dpkg --audit` 为空。
- 整机重启进入 `5.15.0-186-generic`，Docker、Swap 和三项容器全部自动恢复；重启后 Qdrant/Prometheus 健康、三个监控 target 全部恢复为 `up`。
- 为 Qdrant 的非 root 初始化标记增加单文件持久化挂载，清除每次启动的权限警告，没有放宽 UID 或容器 capabilities。
- 远端只上传 `docs/dev-ops/rag-runtime/` 中的基础设施配置，没有上传或部署本地 Java、Vue、Python 业务项目。
- 尚未部署模型服务、Docling/PaddleOCR、`rag-api`、`rag-worker`，也未把本机 Prometheus 接入现有 Grafana；这些属于后续 RAG 应用层与私网连通计划。
