# RAG 运行、配置与故障恢复

## 组件所有权

| 组件 | 位置 | 职责 |
|---|---|---|
| Spring Boot RAG 控制面/Worker | 本地业务项目 | 鉴权、状态机、任务租约、解析/索引编排、检索、审计、Agent 上下文 |
| MySQL | 业务中间件服务器 | 知识库、文档、版本、任务、Profile、Binding、chunk 元数据和审计事实 |
| MinIO | 业务中间件服务器 | 原文件与可追溯对象 |
| Kafka | 业务中间件服务器 | Outbox 事件的至少一次唤醒；消息不是任务真相 |
| Qdrant | RAG 专用服务器 | 可按版本/generation重建的Dense/Sparse索引 |
| Docling | RAG 专用服务器 | PDF/DOCX 结构化解析；Markdown走Java本地解析 |
| Embedding / Reranker | RAG 专用服务器 | 768维Dense向量和Cross-Encoder重排 |
| 模型鉴权网关 | RAG 专用服务器 | 只转发允许的模型/解析业务路径并校验独立密钥 |
| Prometheus / Node Exporter | RAG 专用服务器 | 采集Qdrant、模型、Docling与主机资源；不向公网开放 |

服务器版本、端口、健康检查方式和受控凭据只维护在根目录 `codex.md`。不要把其中密钥复制到环境模板、前端、日志、评测产物或 Git。

## 启动门禁

生产或联调启用 RAG 前至少确认：

1. 最终 MySQL 增量迁移 `docs/dev-ops/mysql/sql/2026-07-18-rag-module.sql` 已经在目标库执行并通过文件内前置/后置审计。
2. MinIO、Kafka、Qdrant、Docling、Embedding、Reranker 均可从 Java 运行主机访问。
3. `AI_RAG_ENABLED=true`；需要本地扫描任务时设置 `AI_RAG_WORKER_ENABLED=true`。
4. Kafka Listener 和 Outbox 分别由 `AI_RAG_KAFKA_LISTENER_ENABLED`、`AI_RAG_OUTBOX_ENABLED` 控制。只开启 Worker 扫描不要求 Kafka Listener 开启。
5. Embedding 模型 revision、768 维、Qdrant collection schema一致；查询加 `query: `，文档加 `passage: `。
6. 生产环境必须为 Qdrant 恢复 API Key，并让Qdrant及模型鉴权网关全部使用TLS、VPN/私网或等价的受控加密链路。当前Qdrant公网匿名、模型网关公网明文HTTP状态仅可用于非敏感联调，API Key不能弥补链路未加密。

使用受限 MySQL option file 执行迁移，避免密码出现在命令行或历史中：

```bash
cd /path/to/Agent-Project
mysql --defaults-extra-file=/path/to/restricted-client.cnf \
  --database=ai_agent_scaffold \
  < docs/dev-ops/mysql/sql/2026-07-18-rag-module.sql
```

`restricted-client.cnf`必须由当前用户所有且权限为0600，至少包含目标host、port、user和password；`--database`必须显式指向经过备份与前置审计的目标库，不能依赖默认库。

本地隔离基准应用的可复现启动入口：

```bash
cd /path/to/Agent-Project
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
mvn -pl ai-agent-scaffold-app -am clean package -DskipTests
bash ai-agent-scaffold-benchmark/scripts/start-local-rag-benchmark-app.sh
```

该脚本只适用于受控开发主机，会从本机 `codex.md` 读取模型密钥、使用本地对象存储并显式关闭Nacos config/discovery、Kafka Listener和Outbox。生产启动不能复制这套隔离参数。修改脚本后必须通过真实启动日志确认没有Nacos远端配置导入；仅看到环境变量并不足以证明隔离。首次upsert/search前，`QdrantVectorStoreAdapter.ensureCollection()`会幂等创建或严格核对唯一Dense/Sparse named-vector schema；schema不符会失败，不能复用错误collection继续写入。

健康核验必须使用真实契约：Qdrant可检查`/healthz`并读取目标collection；Embedding、Reranker和Docling网关刻意阻断部分健康/指标路径，因此应使用无敏感合成输入做最小授权`/embed`、`/rerank`、`/v1/convert/file`请求。某个未开放的`/health`超时或404不能单独证明模型容器失活。具体端口和鉴权头见`codex.md`，探测输出不得包含Key、向量或文档正文。

## 关键配置

RAG核心默认值以 `ai-agent-scaffold-app/src/main/resources/application.yml` 和 `RagProperties` 为准；Servlet上传限制来自具体profile（开发环境位于`application-dev.yml`），Nacos与部署环境变量还可覆盖本地文件。发布记录必须保存启动后的脱敏配置摘要，不能只引用仓库默认值。常用运行边界：

| 类别 | 关键环境变量 | 当前默认/语义 |
|---|---|---|
| Qdrant | `AI_RAG_QDRANT_ENDPOINT`、`AI_RAG_QDRANT_API_KEY`、`AI_RAG_QDRANT_COLLECTION`、`AI_RAG_QDRANT_MAX_CONCURRENCY`、`AI_RAG_QDRANT_BATCH_SIZE` | 默认4并发、64 point批次；生产Key不得为空 |
| Qdrant恢复 | `AI_RAG_QDRANT_TIMEOUT`、`AI_RAG_QDRANT_MAX_RETRIES`、`AI_RAG_QDRANT_TOTAL_TIMEOUT` | 单次3秒、最多2次重试、操作总限时30秒 |
| Embedding | `AI_RAG_EMBEDDING_API_KEY`、`AI_RAG_EMBEDDING_BATCH_SIZE`、`AI_RAG_EMBEDDING_REQUEST_TIMEOUT`、`AI_RAG_EMBEDDING_TIMEOUT` | 默认16批；单次和总时限分别配置；重试/退避由`RagProperties`统一校验 |
| Reranker | `AI_RAG_RERANKER_API_KEY`、`AI_RAG_RERANKER_TIMEOUT`、`AI_RAG_RERANKER_REQUEST_TIMEOUT`、`AI_RAG_RERANKER_BATCH_SIZE`、`AI_RAG_RERANKER_REQUEST_BATCH_SIZE` | 业务候选上限16；HTTP子批默认3并串行全局排序 |
| Docling | `AI_RAG_DOCLING_TIMEOUT`、`AI_RAG_DOCLING_MAX_CONCURRENCY`、`AI_RAG_DOCLING_MAX_PAGES` | 默认120秒、1并发、500页 |
| Worker | `AI_RAG_WORKER_SCAN_BATCH_SIZE`、`AI_RAG_WORKER_LEASE_DURATION_MS`、`AI_RAG_WORKER_HEARTBEAT_INTERVAL_MS` | 默认10任务、180秒租约、30秒心跳 |
| Chunk | `AI_RAG_WORKER_CHILD_MAX_TOKENS`、`AI_RAG_WORKER_PARENT_MAX_TOKENS`、`AI_RAG_WORKER_OVERLAP_CHARS` | 默认420/1400 token、160字符overlap |
| 审计 | `AI_RAG_AUDIT_STORE_QUERY_TEXT`、`AI_RAG_AUDIT_STORE_CITATION_CONTENT` | 默认均为false，避免持久化敏感正文 |
| HTTP上传 | `MULTIPART_MAX_FILE_SIZE`、`MULTIPART_MAX_REQUEST_SIZE` | dev profile默认50MB/52MB；其他profile、Nacos与反向代理必须显式设置且不低于业务策略需要 |

约束：单次请求 timeout 不得大于总 deadline；退避不占并发许可，但必须消费总 deadline；401、请求格式、响应schema和维度错误不可重试。

## 摄取状态与恢复

正常主链：

```text
received -> validating -> parsing -> chunking -> embedding -> indexing -> verifying -> completed
```

恢复原则：

- 每次 claim 增加 fencing token；checkpoint、heartbeat、完成和失败都要求当前 lease/fence/revision。
- 外部调用前后检查取消；取消后的候选 generation 不得激活，已产生向量按 generation 清理。
- Embedding 与 Qdrant 使用稳定 chunk/point ID，重试为幂等 upsert。
- Kafka 只传任务标识，消费者仍须向 MySQL claim；重复消息不能直接重复副作用。
- 新版本 build-then-publish；失败或取消不破坏旧 active generation。

排障顺序：

1. 先查任务 `status/stage/errorCode/attempt/revision/lease`，不要只看 Kafka 消息。
2. 核对文档、版本、任务三者的 target/active generation 和状态是否一致。
3. 按 errorCode 区分确定性输入错误与可重试基础设施错误。
4. 对模型/Qdrant先做最小授权请求和健康检查，再决定是否重启；错误的健康路径不能作为服务失活证据。
5. 保留失败任务、checkpoint、日志时间窗和索引计数；不要删除后再声称恢复成功。

## 检索故障语义

- Dense/Sparse/Qdrant不可用：返回稳定业务错误，不伪装成空召回。
- Reranker超时或可恢复网关错误：可选绑定显式 `degraded=true` 并回退融合结果；质量评测的Rerank组禁止把降级样本当成功。
- required Binding 不可用或出现租户/版本 scope violation：fail closed，模型不得继续无证据回答。
- 审计失败当前不覆盖成功检索结果，但必须记录稳定日志；`serviceMs`仍覆盖审计尝试。

## MySQL 迁移边界

现有数据体量很小，迁移业务 MySQL 在容量上可行，但它只能改善配置、hydration和同步审计的公网往返，不能修复 Embedding/Reranker 推理挂起。

迁移必须分为：迁移前延迟/资源基线、版本一致的受限恢复实例、全量备份校验、增量追平、停写窗口、表/行/hash核验、应用连接切换、登录/会话/RAG/调度/消息 E2E、旧库只读回滚窗口。不要使用双主写入，也不要默认把 Kafka、MinIO、Nacos、XXL-JOB 数据库和观测栈一起迁到推理服务器。

## 发布与回滚

- 不原地覆盖正在运行的 JAR；使用版本化产物和原子切换或容器替换。
- 发布前记录 Git commit、JAR SHA-256、JDK、配置摘要和迁移版本；密钥只记录环境变量名。
- 数据库迁移为前向兼容；Qdrant新模型/切分版本使用新 generation 或 collection，验证后切换。
- 回滚应用时保留新数据结构；回滚检索时切回旧 active generation，确认查询后再异步清理失败代次。
- 任何跨租户命中、取消后继续外部副作用、迁移核验失败或质量门禁失败都必须停止发布。
