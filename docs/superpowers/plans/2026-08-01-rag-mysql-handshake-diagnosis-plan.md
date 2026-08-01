# RAG 服务器 MySQL 握手中断诊断计划

## 目标

定位通过 `103.205.240.84` 公网连接 MySQL 时出现 `Lost connection to MySQL server at 'reading initial communication packet'` 的具体故障层级。本轮只诊断，不修改服务器、防火墙、MySQL 或 Docker 配置。

## 执行计划

1. 阅读 `codex.md` 中 RAG 服务器、MySQL 部署方式与 SSH 别名，不输出敏感值。
2. 从本机检查 `103.205.240.84` 的 ICMP/SSH/3306 TCP 可达性和 MySQL 首包。
3. 通过 SSH 只读检查服务器时间、系统资源、3306 监听地址、Docker 容器状态/重启/OOM 与 MySQL 最近日志。
4. 如能进入 MySQL，核对引擎版本、`bind_address`、`skip_networking`、`require_secure_transport`、`max_connections`、当前连接数和主机缓存。
5. 将根因、证据、影响和安全修复建议追加到本计划；未获得“修复”授权前不修改外部状态。

## 判定口径

- 3306 超时：优先检查云防火墙、系统防火墙、NAT 与端口映射。
- TCP 建连后无 MySQL greeting 或立即 EOF：优先检查 MySQL 进程/容器、反向代理、资源不足、host cache 封禁和服务端日志。
- 收到 greeting 后认证失败：再检查用户 host、认证插件、TLS 与密码；不把握手前 EOF 当成密码错误。

## 实际操作记录

### 2026-08-01 只读诊断

1. 已核对 `codex.md` 和本机 SSH 配置：RAG MySQL 公网地址为 `103.205.240.84:3306`，服务器 MySQL 白名单仅允许 `223.104.79.0/24` 和 `113.84.136.229/32`；本机 SSH 别名可通过 443 登录。
2. 当前本机公网 IPv4 为 `223.104.87.137`，不属于上述任一白名单。
3. 本机对服务器 22、443、3306 的 TCP connect 均能建立，但公网 3306 在 5 秒内收不到 MySQL greeting；对本机 SSH 转发 `127.0.0.1:13306` 可收到 78 字节 greeting，协议版本 10，MySQL 版本 `8.0.46`。
4. 服务器 `DOCKER-USER` 实际规则与文档一致：前两条 ACCEPT 上述白名单，第三条 DROP 其余所有 3306 来源；DROP 计数已累计 66,956 包。`ai-agent-rag-mysql-firewall.service` 为 enabled 且 active(exited)，规则恢复成功。
5. `rag-mysql` 容器运行 12 天且 healthy，restartCount=0、OOM=false；宿主机 3306 在 `0.0.0.0` 正常监听。服务器可用内存 8.8 GiB、Swap 未使用、根盘使用率 43%，无资源耗尽证据。
6. MySQL 引擎为 `8.0.46`，`bind_address=*`、`skip_networking=OFF`、`require_secure_transport=OFF`、`max_connections=80`、`max_connect_errors=100`；当前 Threads_connected=25、Threads_running=2，历史 Max_used_connections=60，`Connection_errors_max_connections=0`，不存在当前连接数耗尽证据。
7. 根因判定：当前开发网络出口 IP 从已授权网段漂移到 `223.104.87.137`，连接被 `DOCKER-USER` 的 3306 默认 DROP 规则截断，客户端因收不到 MySQL 初始 greeting 而报 `reading initial communication packet`。这不是密码、认证插件、MySQL 容器宕机或 max_connections 耗尽。
8. 本轮没有修改服务器防火墙、MySQL、Docker、SSH 或本机转发配置。现有 `127.0.0.1:13306` SSH 转发可正常收到 MySQL greeting，可作为当前安全连接路径。
