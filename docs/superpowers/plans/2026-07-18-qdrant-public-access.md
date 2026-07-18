# Qdrant 公网访问执行计划

## 目标

按用户要求，将 `RAG-Server` 的 Qdrant HTTP/Dashboard 端口 `6333` 以无 API Key 方式开放公网，公网地址为 `http://103.205.240.84:6333`。

## 风险确认与范围

- 当前 Qdrant 未启用 API Key；开放后任何能够连接公网地址的人都可以读取、写入和删除向量数据。
- 仅开放 HTTP/Dashboard `6333/tcp`。
- Qdrant gRPC `6334`、Prometheus `9090`、Node Exporter `9100` 不开放公网。
- 不上传或部署 Java、Vue、Python 业务项目，不修改现有 collection 业务数据。

## 执行计划

1. 核对远端 Compose、容器健康、端口监听、UFW 与云侧公网可达状态，记录变更前基线。
2. 备份远端 Compose 与防火墙状态，修改本地 Compose，将 Qdrant `6333` 从回环绑定改为公网绑定，保留 `6334` 回环绑定。
3. 完成本地 YAML、远端 Compose 配置校验，再同步中间件配置并只重建 Qdrant；放行 UFW `6333/tcp`。
4. 从服务器本机和外部网络验证 Dashboard、匿名 collection API 与临时 collection 读写；测试数据完成后清理。
5. 重启 Qdrant，验证健康状态、持久化、公网恢复、Prometheus 采集与其余端口仍不可达。
6. 追加实际执行记录，更新 `codex.md`，只提交本轮相关文件并使用中文提交信息。

## 回滚方案

- 恢复远端备份 Compose，将 `6333` 重新绑定 `127.0.0.1`。
- 删除 UFW 的 `6333/tcp` 放行规则并重建 Qdrant。
- 回滚不得删除 Qdrant storage、snapshot 或任何业务 collection。

## 执行记录

### 2026-07-18：开放 Qdrant 6333 公网访问

- 变更前确认 Qdrant、Prometheus、Node Exporter 均正常；`6333/6334/9090` 只监听回环地址，UFW 仅允许 SSH，三个端口从外部均不可达。
- 本地 Compose 仅将 Qdrant HTTP 映射由 `127.0.0.1:6333:6333` 改为 `0.0.0.0:6333:6333`；Qdrant gRPC `6334` 与 Prometheus `9090` 保持回环绑定。
- 本地 YAML 解析和差异检查通过；远端 `docker compose config --quiet` 通过后才替换运行配置。
- 服务器旧 Compose 和 UFW 基线已备份至 `/opt/ai-agent-rag/backups/qdrant-public-20260718-114951`，目录权限为 `700`；部署脚本包含恢复 Compose、撤销 UFW 规则和重建 Qdrant 的失败回滚逻辑。
- UFW 新增 `6333/tcp` 公网放行规则，只重建 Qdrant 容器；未上传或部署任何 Java、Vue、Python 业务项目，也未修改业务 collection。
- 服务器监听验证：Qdrant HTTP 为 `0.0.0.0:6333`，Qdrant gRPC 为 `127.0.0.1:6334`，Prometheus 为 `127.0.0.1:9090`。
- 从服务器外部访问公网 IP，匿名 collection API、Dashboard、创建临时 collection、重启后读取、删除临时 collection 均返回 HTTP 200；临时 collection 已清理。
- Qdrant 重启后恢复 healthy，Prometheus 保持 healthy，采集查询为 `prometheus=1`、`qdrant=1`、`node-exporter=1`。
- 外部复测 `6334` 与 `9090` 仍不可达，未扩大到计划外端口。
- 最终复核曾出现一次公网请求 8 秒超时；当时容器、本机 HTTP、监听和 UFW 均正常，随后从外部连续三次请求均返回 200，判定为瞬时公网链路波动，并保留该事实供后续观测。
- `codex.md` 已记录无认证公网访问的实际边界和高风险提示；该文件按既有规则仅供本地使用，不纳入 Git。
