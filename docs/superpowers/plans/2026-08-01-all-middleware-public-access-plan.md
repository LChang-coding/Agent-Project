# 全部中间件公网可达改造计划

## 目标

将 `codex.md` 中登记的所有中间件 IP/端口改为允许任意公网来源访问，不再依赖开发机固定 IP 白名单或仅回环地址绑定。保留现有服务级账号、密码、API Key 和 TLS 能力；不上传本地业务项目。

## 涉及服务器

1. `RAG-Server` / `103.205.240.84`：MySQL、Qdrant HTTP/gRPC、Embedding、Reranker、Docling、Prometheus、Node Exporter。
2. `CentOS-Server` / `69.165.65.123`：Kafka、MinIO API/Console、Nacos、XXL-JOB、Grafana/Loki/Alloy 等 `codex.md` 登记观测与调度端口。

## 执行计划

1. 从 `codex.md`、Docker Compose、宿主机监听与防火墙规则生成“服务→端口→当前绑定→当前鉴权”清单。
2. 在任何改动前将两台服务器的 Compose/服务配置、UFW/firewalld/iptables 和监听端口快照复制到服务器本地时间戳备份目录，不下载敏感文件。
3. RAG 服务器：移除 MySQL 3306 来源白名单 DROP；将 Qdrant gRPC 6334、Prometheus 9090 及 Node Exporter 9100 从回环/Docker 内网改为宿主机 `0.0.0.0` 发布；核对现有 6333/8081/8082/5001 公网端口。
4. CentOS 服务器：将 `codex.md` 登记中间件端口全部允许任意 IPv4 来源，修正任何仅 `127.0.0.1` 或指定来源的发布/防火墙规则；Kafka 同时核对 `advertised.listeners` 仍为公网可回连地址。
5. 使用最小化重建/重载生效改动，避免重启无关容器；逐服务检查 healthy、restartCount、OOM 和日志。
6. 从本机按公网 IP 对每个端口做 TCP/协议层验收：MySQL greeting/TLS、HTTP 状态、Qdrant gRPC TCP、Kafka metadata、MinIO/Nacos/XXL-JOB/Grafana 响应。
7. 更新 `codex.md` 的访问边界、安全风险、配置路径和验收时间；追加本计划的实际操作、端口清单和验收数据。
8. 对每个重大服务器闭环进行中文本地 Git 提交，不提交日志、敏感备份或无关工作树文件。

## 验收门禁

- 每个登记端口必须绑定 `0.0.0.0` 或等价全接口，防火墙不得再按来源 IP 限制。
- 端口开放后服务必须保持 healthy，不允许新增 OOM、反复重启或持续 5xx。
- 有鉴权的服务保留鉴权；本轮不为达成“可达”而关闭密码/API Key。
- 已知无鉴权服务必须在 `codex.md` 显式标注公网风险。
- SSH 管理端口不得在改动中断；每次防火墙重载后立即新建 SSH 会话复核。

## 回滚标准

- 新配置导致任一既有服务无法启动、健康检查连续失败或 SSH 新会话失败时，使用改动前快照回滚当前服务。
- 只回滚本次修改的端口发布/防火墙配置，不回滚业务数据。

## 实际操作记录

### 1. RAG-Server（103.205.240.84）

- 改动前快照：`/opt/ai-agent-rag/backups/public-access-20260801T114500Z`，包含 Compose、MySQL 防火墙脚本与 systemd unit、UFW、iptables、监听端口和容器状态。未删除原配置或数据。
- 将 Qdrant gRPC `6334`、Prometheus `9090` 从 `127.0.0.1` 改为 `0.0.0.0` 发布，新增 Node Exporter `0.0.0.0:9100:9100`；只强制重建 `qdrant`、`prometheus`、`node-exporter`。
- 将 `/usr/local/sbin/ai-agent-rag-mysql-firewall` 从两个开发网段的 ACCEPT + 其余 DROP 改为任意来源的 `tcp dpt:3306 ACCEPT`；systemd 服务保持 enabled/active，并会在 Docker 启动后幂等恢复公网放行规则。
- UFW 新增任意 IPv4/IPv6 来源的 `3306/6334/9090/9100` 放行；原有 `6333/8081/8082/5001` 任意来源规则保留。
- 容器验收：MySQL、Qdrant、Prometheus 及三个模型/解析服务均 running/healthy；本次重建服务 restartCount=0、OOM=false；Node Exporter running。
- 从改造前不在白名单的当前公网进行验证：`3306/6333/6334/5001/8081/8082/9090/9100` TCP 全部成功；MySQL 返回 8.0.46 protocol 10 greeting；Qdrant `/healthz`=200；Prometheus `/-/ready`=200；Node Exporter `/metrics`=200。
- 鉴权回归：Embedding、Reranker、Docling 不带 API Key 请求均返回 401，证明“任意 IP 可达”没有变成匿名业务调用。Qdrant、Prometheus、Node Exporter 本来无认证，风险已在 `codex.md` 显式记录。

### 2. CentOS-Server（69.165.65.123）

- 核对 MySQL `3306`、Redis `6379`、XXL-JOB `8080`、Nacos `8848/9848/9849`、MinIO `9000/9001`、Kafka `9094`、Grafana `13000`、Loki `3100`。firewalld 未启用，`DOCKER-USER` 无来源限制；除 Loki 外均已在全网卡监听。
- 改动前快照：`/root/middleware-public-access-backups/20260801T074700-0400`；将 Loki 从 `127.0.0.1:3100` 改为 `0.0.0.0:3100`，只重建 `ai-agent-loki`。WAL/checkpoint 恢复完成且 errors=false，公网 `/ready` 最终返回 200。
- Kafka 保留 `EXTERNAL://:9094`、`advertised.listeners=EXTERNAL://69.165.65.123:9094`、SASL_SSL/SCRAM-SHA-512；公网 TLS 1.3 握手成功。容器存在本次任务之前的 `OOMKilled=true` 历史状态，但当前 running、restartCount=0、连接及广播正常；本次不为清除历史标记而重启有数据服务。
- 发现 XXL-JOB 容器虽 running，但历史公网登录请求已使 128 PID 上限耗尽，出现 `unable to create native thread` 并导致 HTTP 无响应。备份为 `/root/middleware-public-access-backups/20260801T194739/xxl-job-docker-compose.yml.before-thread-guard`，新增 Tomcat `threads.max=48`、`min-spare=4`、`accept-count=100`、`max-connections=500`，只重建 `xxl-job-admin`。启动后 pids=36、OOM=false，公网首页返回 302 登录跳转。
- 公网协议验收：上述所有 TCP 端口连接成功；Nacos `/nacos/`=200；MinIO health=200；MinIO Console=200；Grafana `/api/health`=200；Loki `/ready`=200；XXL-JOB=302；Kafka TLS 握手成功。

### 3. 文档与边界

- 已更新本地 `codex.md`：记录两台服务器的公网地址、任意来源边界、保留的应用层鉴权、无鉴权端口风险、配置路径与 XXL-JOB 线程护栏。`codex.md` 包含敏感信息且按项目约束未被 Git 跟踪，不加入提交。
- 未上传本地项目，未迁移数据库，未删除任何服务器或本地文件，未改动业务代码。
