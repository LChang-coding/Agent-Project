# RAG 模型与解析中间件部署计划

## 目标

在不上传或部署本地 Java、Vue、Python 业务项目的前提下，为 `RAG-Server` 增加可由本机 Java 项目远程调用的 Embedding、Reranker 与 Docling Serve 环境，并完成认证、资源约束、监控、重启恢复和接口验收。

## 部署范围

- `rag-embedding`：Hugging Face Text Embeddings Inference CPU 服务，首期候选模型 `intfloat/multilingual-e5-base`。
- `rag-reranker`：Hugging Face Text Embeddings Inference CPU 服务，首期候选模型 `BAAI/bge-reranker-base`。
- `rag-docling`：Docling Serve CPU 服务，提供 PDF、DOCX、Markdown 结构化解析接口。
- 扩展现有 Prometheus 抓取、Docker Compose、UFW、日志轮转、健康检查和资源限制。
- 模型权重与缓存持久化在服务器 `/opt/ai-agent-rag/models`，不写入 Git。

## 明确不做

- 不上传或运行本地 Java/Vue/Python 项目、源码、Jar 或业务 Docker 镜像。
- 不部署 `rag-api`、`rag-worker`、Spring Boot、MySQL、Kafka、MinIO、Redis 或 Elasticsearch。
- 不修改现有业务数据库、MinIO 对象、Kafka Topic 或 Qdrant 业务 collection。
- 不在未经验证时声称 BGE-M3 lexical sparse 已由 TEI 支持；首期以稳定 Dense Embedding + Reranker 接口为验收范围。

## 执行计划

1. 只读审计服务器 CPU 指令集、内存、磁盘、Swap、Docker、容器实时资源、端口、防火墙及当前 Compose，确认容量和冲突。
2. 核验官方镜像标签、CPU 架构和模型兼容性；固定镜像版本与 digest，记录模型 revision/文件摘要，若候选不兼容则停止部署并据实调整计划。
3. 生成三项独立随机 API Key，仅写入远端权限 `600` 的环境文件和本地 Git 排除的 `codex.md`；Git 只保留变量名清单。
4. 在本地维护可复现 Compose/Prometheus 配置：非 root（镜像允许时）、cap drop、no-new-privileges、内存/CPU/PID 上限、低并发、健康检查、持久化模型缓存和日志轮转。
5. 备份远端 Compose、Prometheus、`.env` 与 UFW 基线；按 Embedding → Reranker → Docling 顺序拉取和启动，每一步健康后再继续，失败时恢复旧配置且不删除既有 Qdrant 数据。
6. 仅开放本机 Java 所需的 `8081/8082/5001` 公网入口，并强制 Bearer/API Key；模型下载、Prometheus 指标端口和容器内部端口不对公网开放。
7. 从服务器本机与外部网络验证：无认证拒绝、正确认证成功、Embedding 维度/确定性、Reranker 排序、三类最小文档解析、健康与指标、并发限制和错误输入。
8. 重启受影响容器并执行整机重启恢复验证；复测接口、模型缓存、Prometheus targets、端口边界、资源占用和现有 Qdrant 功能。
9. 追加真实执行记录，更新 `codex.md` 的版本、地址、密钥、目录、资源、运维与调用示例；只提交无密钥配置和计划文档，中文提交。

## 初始资源预算

- Embedding：最多 5 CPU、3 GiB、单请求批次与并发受限。
- Reranker：最多 5 CPU、3 GiB、TopK 小批次与并发受限。
- Docling：最多 4 CPU、4 GiB、首期单并发。
- 现有 Qdrant 保留数据安全余量；上线前以实测 RSS、P95 和 OOM 行为调整，不能仅按理论上限相加。

## 回滚方案

- 恢复带时间戳的远端 Compose、Prometheus、`.env` 和 UFW 状态，停止新增三个容器。
- 保留已下载模型缓存用于复核，除非另行确认，不执行数据目录或模型目录删除。
- 回滚不得影响 Qdrant、Prometheus、Node Exporter、业务 collection、snapshot 或既有公网 `6333` 规则。

## 执行记录

### 2026-07-18 服务器审计与选型

- 服务器：Ubuntu 22.04、Linux `5.15.0-186-generic`、16 vCPU、15 GiB RAM、2 GiB Swap、39 GiB 根盘，CPU 支持 AVX2。
- 部署前保留并复验既有 Qdrant、Prometheus、Node Exporter；未接触 Qdrant collection、snapshot、MinIO、Kafka、数据库或任何业务数据。
- 固定 TEI `cpu-1.9.3`、Docling Serve CPU `v1.26.0`、Nginx `1.28.0-alpine` 及镜像摘要；Embedding 与 Reranker 固定 Hugging Face 模型 revision，避免浮动更新。
- 本地 Java/Vue/Python 项目、Jar、源码和业务镜像均未上传服务器；远端只增加运行环境、模型缓存和网关配置。

### 2026-07-18 部署操作

- 创建时间戳备份 `/opt/ai-agent-rag/backups/rag-model-middleware-20260718-130106`，保留部署前 Compose、Prometheus、环境文件和 UFW 基线。
- 新增 `rag-embedding`：`intfloat/multilingual-e5-base@d128750597153bb5987e10b1c3493a34e5a4502a`，768 维归一化向量，4 CPU、3 GiB、8 并发、8192 batch tokens、16 client batch、2 tokenizer workers。
- 新增 `rag-reranker`：`BAAI/bge-reranker-base@2cfc18c9415c912f9d8155881c133215df768a70`，4 CPU、3 GiB，并复用 TEI 的受限并发参数。
- 新增 `rag-docling`：CPU 单 Worker，4 CPU、4 GiB、512 PID、512 MiB shm、1 GiB `/tmp`，关闭 UI，限制单文件 50 MiB、单文档 500 页，首期调用关闭 OCR。
- 新增非 root Nginx 鉴权网关：Embedding `8081`、Reranker `8082` 使用独立 Bearer Token，Docling `5001` 使用独立 `X-Api-Key`。三个后端容器没有发布宿主机端口，健康与指标仅 Docker 内网可达。
- 三个密钥分别保存在 `/opt/ai-agent-rag/secrets/*-api-key`（权限 400），Nginx 映射文件权限 440；Compose 环境变量和启动命令中不含密钥。真实值只同步至本地 Git 排除的 `codex.md`。
- 扩展 Prometheus 为六个采集目标；新增 UFW `8081/8082/5001` 规则；持久化 `vm.swappiness=10`；所有新增容器设置日志轮转、健康检查、资源/PID 限制和 `restart: unless-stopped`。

### 部署中发现并闭环的问题

- 首次使用 TEI 内置 API Key 时，TEI 会把完整密钥写入启动参数日志。该容器未发布端口且网关尚未启动，密钥没有公网暴露；立即停止并删除容器、轮换全部三个密钥，并把认证统一移到 Nginx 网关。最终检查四个相关容器的环境变量与启动命令，敏感引用计数均为 0。
- 网关首次启动出现 Nginx 重复 `pid` 指令，移除命令行中的额外 pid 配置；随后因长密钥触发 `map_hash_bucket_size` 不足，增加独立 HTTP settings 配置并保证加载顺序。
- 非 root Nginx 无法读取 root:root 640 的 bind mount 配置，改为 root:101 640；网关随后以 UID 101 健康运行且重启次数为 0。
- Prometheus 配置通过原子替换安装后，运行中容器仍绑定旧 inode；强制重建 Prometheus 容器后加载新配置，六个 targets 全部恢复为 `up`。

### 功能与安全验收

| 验收项 | 结果 |
|---|---|
| Embedding | 三条输入及重复输入通过；维度 768、L2 norm `1.000000`、重复结果一致 |
| Reranker | 企业知识库相关文本排第 1，索引 0，分数严格降序 |
| Docling Markdown | `status=success`、0 errors |
| Docling DOCX | 标记内容成功解析，耗时约 0.021 秒 |
| Docling PDF | 标记内容成功解析，耗时约 10.33 秒 |
| 未认证与错密钥 | 三个网关均返回 401 |
| 正确认证公网调用 | `103.205.240.84:8081/8082/5001` 均完成真实调用 |
| 内部端点隔离 | TEI `/health`、`/metrics` 与 Docling `/docs`、`/metrics` 对公网返回 404 |
| 错误输入 | Embedding 非法请求体返回 422，服务保持 healthy |
| 瞬时并发保护 | 20 路同源并发得到 9×200、2×429、9×503，限流/后端背压生效，服务未重启 |
| 密钥落点 | Embedding、Reranker、Docling、网关的 env/cmd 均无密钥引用 |
| 非 root | Embedding/Reranker UID 65532、Docling UID 1001、Nginx UID 101 |

### 资源与恢复验收

- 稳态实测：Embedding 约 1.85 GiB、Reranker 约 1.92 GiB、Docling 约 1.0 GiB、网关约 13 MiB；主机可用内存约 9.9 GiB，Swap 使用 0。
- 磁盘使用约 14/39 GiB（34%），剩余约 26 GiB；Docker 镜像约 6.35 GB，模型缓存分别持久化在独立目录。
- 容器级重启后，Embedding 768 维、Reranker 排序、Docling 转换均再次通过，模型直接从本地缓存恢复。
- 整机重启后 Docker 为 active，七个容器自动恢复；三个公网接口再次实际调用成功，未认证仍为 401，Prometheus 的六个目标值均为 1，`vm.swappiness=10` 保持生效。

### 最终变更边界

- 服务器端新增模型、解析、鉴权网关和监控配置；未部署 `rag-api`、`rag-worker`、Spring Boot 或任何本地业务项目。
- Git 仅保存无密钥的 Compose、Prometheus、Nginx 示例、sysctl 配置和本执行记录；真实密钥不进入 Git。
