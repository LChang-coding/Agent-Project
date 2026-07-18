# RAG Java 模块与评测闭环 Target 计划

## 目标

在不上传本地业务项目到服务器的前提下，严格沿用现有模块化单体、租户上下文、MinIO、Kafka、MySQL、Qdrant、远程 Docling、Embedding 和 Reranker 基建，完成可上线的多租户 RAG Java 模块、租户管理员前端、自动化测试、公开数据集评测、消融实验、性能压测与瓶颈定位。

完成结论必须由代码、数据库迁移、自动化测试、原始评测记录、监控数据和可复现命令共同证明，不允许根据预期补写或美化数据。

## 不可变约束

- 先遵循 `codex.md`，依赖方向保持 `trigger -> domain`、`infrastructure -> domain port`，由 `app` 装配。
- JWT 与 `TenantContextHolder` 是可信身份来源；全部知识库、文档、任务、分块、检索和引用记录必须按租户隔离。
- 本地 Java/Vue 源码、Jar 和业务镜像不上传 RAG 服务器；本地项目只通过受控公网接口调用已部署中间件。
- 真实 SSH、MinIO、数据库、Kafka、模型和网关密钥不得进入 Git、日志、前端或评测结果。
- 每个阶段执行前在本计划追加阶段计划，阶段结束后追加真实操作、验证、失败和遗留项。
- 每个重大闭环使用中文本地提交，只暂存本阶段相关文件，保留工作区其他改动。
- 单元测试失败不能被忽略；外部依赖导致的集成或端到端测试失败要继续定位并留痕，不能编造通过结果。

## 总体架构目标

### 文档摄取链路

租户管理员上传 -> MinIO 原件 -> MySQL 文档/版本/任务 -> Kafka 摄取事件 -> 租约 Worker -> Docling 解析 -> 规范化 -> 结构化分块 -> Embedding 批处理 -> Qdrant 幂等写入 -> 任务完成/可重试/可取消。

### 查询链路

可信租户上下文 -> 查询规范化/可选改写 -> Dense 与 Sparse/关键词候选 -> 租户和知识库过滤 -> 融合与去重 -> 可选 Reranker -> 邻接块/结构扩展 -> Token 预算组装 -> 引用封装 -> Agent/会话上下文。

### 数据与状态边界

- MySQL 保存知识库、文档、版本、摄取任务、分块元数据、评测运行和审计信息。
- MinIO 保存上传原件、规范化解析产物、评测语料与可追溯测试工件。
- Qdrant 保存可重建的 Dense/Sparse 检索索引，不作为业务事实源。
- Kafka 只传递任务标识与版本，不携带大文档内容。

## 分阶段执行计划

### 阶段 0：现状审计与基线冻结

1. 审计 Maven 模块、Spring Boot 版本、数据库迁移方式、现有 RAG 表、附件/MinIO/Kafka/任务、Agent/会话、租户权限和前端路由。
2. 检查工作树、已有测试、启动方式和远端中间件可达性；记录不能覆盖的用户改动。
3. 输出代码扩展点、复用点、冲突表、数据库迁移策略和第一批实现切片。
4. 在改动 RAG 代码前执行可运行的后端/前端基线测试并记录结果、耗时和失败原因。

验收证据：审计记录、基线命令与输出摘要、明确的包/表/API/页面映射、阶段计划追加记录。

### 阶段 1：领域模型、数据库与外部端口

1. 设计并迁移知识库、文档、文档版本、摄取任务、分块元数据、检索审计等表，增加租户联合索引、幂等键、乐观锁和任务租约字段。
2. 在 Domain 定义实体、值对象、状态机、Repository 与 Docling/Embedding/Reranker/Qdrant/MinIO/Kafka Port。
3. 实现配置校验、统一超时/重试边界和不会泄露密钥的错误模型。
4. 使用单元测试验证状态流转、租户隔离、幂等、取消与版本冲突。

验收证据：迁移可重复执行、领域测试通过、无反向依赖、中文提交。

### 阶段 2：文档摄取闭环

1. 实现 PDF、DOCX、Markdown 上传、格式/大小校验、MinIO 原件保存和文档版本创建。
2. 实现 Kafka 任务投递与消费、数据库抢占租约、心跳、checkpoint、指数退避、死信和取消屏障。
3. 调用 Docling，保存规范化产物；实现结构感知分块、稳定 chunkId、批量 Embedding、Qdrant upsert/delete/rebuild。
4. 保证失败恢复不会产生重复向量，取消后不得继续调用后续中间件或污染活动版本。
5. 提供状态、失败详情、重试、取消、删除和重建 API。

验收证据：三种格式端到端通过；故障注入、重复事件、取消竞态和租约接管测试；Qdrant 与 MySQL 数量/版本一致。

### 阶段 3：检索与 Agent 集成闭环

1. 实现 Dense 基线、Sparse/关键词基线、RRF/加权融合、元数据过滤、去重和邻接块扩展。
2. 实现可配置 Rerank、TopK、候选数、阈值、上下文 Token 预算和引用封装。
3. 接入现有 Agent/会话链路，保持流式/非流式、取消、上下文压缩和 Token 统计语义。
4. 记录各阶段耗时、候选数、命中、降级、超时及引用，不记录完整敏感正文。

验收证据：固定查询的可重复检索结果、租户越权为零、引用可回溯、Agent 回答携带来源、中文提交。

### 阶段 4：租户管理员前端

1. 增加知识库列表、创建/编辑/删除、文档上传、任务状态、失败重试/取消/重建和删除入口。
2. 增加检索调试页，显示召回阶段、融合分数、Rerank 分数、引用和耗时，但不暴露服务密钥或跨租户信息。
3. 修复按钮无反馈和组件覆盖问题，为上传、解析、取消和删除提供明确的 pending/success/error 状态。
4. 完成响应式布局、权限路由、空态、错误态和可访问性验证。

验收证据：前端构建、组件测试、浏览器关键路径和截图/录屏留痕、中文提交。

### 阶段 5：公开数据集与评测工具链

1. 联网核验公开 RAG/检索数据集的来源、版本、许可、语言、标准答案和 relevance 标注；优先选择可自动复核的中文、英文、单跳、多跳和无答案子集。
2. 保存下载清单、原始 URL、日期、版本、许可证、SHA-256、样本筛选脚本和确定性随机种子。
3. 将语料转换为 PDF、DOCX、Markdown 三种受支持格式，保存转换器版本、文件哈希、页数/字数及语义一致性检查。
4. 实现 Recall@K、Precision@K、HitRate@K、MRR、MAP、nDCG、引用准确率、Faithfulness 等指标；LLM Judge 必须保存模型、提示词、原始输出和人工抽检结果。

验收证据：可重复下载/转换/导入/查询/计分命令，数据清单与原始逐题结果，不提交许可禁止再分发的大型语料。

### 阶段 6：消融实验与质量评测

固定代码版本、数据集、查询集、索引版本、随机种子和硬件，依次测试：

- Dense only。
- Sparse/关键词 only。
- Dense + Sparse，不同融合方式和权重。
- 有/无 Reranker。
- 有/无查询改写。
- 不同 chunk size/overlap、TopK、候选数、阈值、邻接扩展和结构元数据。

每组记录质量指标、耗时、资源、错误和逐题差异，输出绝对值、相对变化、样本量、置信区间和失败案例，不用单次波动下结论。

验收证据：原始 JSONL/CSV、配置快照、聚合脚本、差异报告和可复现命令。

### 阶段 7：性能压测与瓶颈定位

1. 摄取链路按格式、文件大小、页数、chunk 数、Embedding batch 和 Worker 数进行单线程及阶梯并发测试。
2. 查询链路测试冷/热缓存、不同 TopK、混合检索、Rerank、并发 1/2/4/8/16 等梯度。
3. 每轮记录预热、线程、并发、持续时间、请求量、端点、请求体摘要、超时、P50/P95/P99、吞吐、错误率。
4. 同步采集 Java 阶段计时、线程池/连接池、GC/JFR、MySQL、MinIO、网络、Docling、Embedding、Qdrant、Reranker 与主机 CPU/内存/磁盘。
5. 通过单组件旁路、受控参数变化和相关性证据定位瓶颈，优化后重复同一实验并报告差异。

验收证据：压测脚本、原始时序数据、环境清单、瓶颈证据链、优化前后报告；不能只给平均值或主观结论。

### 阶段 8：上线审计与最终交付

1. 对功能、租户隔离、认证、取消、幂等、超时、重试、日志脱敏、数据库迁移和回滚逐项审计。
2. 执行相关模块单元/集成/E2E、前端构建与浏览器测试，复核所有报告可从原始数据重算。
3. 更新 `codex.md`、运维文档、API 文档、评测方法、已知限制和容量建议。
4. 逐项对照本计划确认完成证据，未完成项不得包装为完成。

验收证据：最终测试矩阵、部署/回滚说明、指标报告、瓶颈结论和中文提交。

## 计划产物目录

```text
docs/superpowers/plans/                 阶段计划与真实执行记录
docs/rag/                               架构、API、运维、评测方法和最终报告
tools/rag-eval/                         数据集清单、转换、导入、评分和压测工具
artifacts/rag-eval/                     本地原始结果（按体积和许可证决定是否 Git 跟踪）
```

## 阶段 0 本轮执行计划

1. 只读扫描模块、构建文件、RAG 相关代码、迁移、前端、测试和运行脚本。
2. 并行审计后端领域/基础设施、数据库/任务链路、前端/测试和评测工具现状。
3. 运行不写外部业务数据的构建与基线测试，记录耗时及失败。
4. 将审计结果和阶段 1 的精确文件级实施计划追加到本文档。
5. 若审计形成独立闭环，中文提交计划与审计记录；随后进入阶段 1。

## 执行记录

### 2026-07-18 启动

- 已确认本次 Target 覆盖完整 Java RAG、租户管理员前端、公开数据集、质量消融、性能压测和瓶颈定位。
- 已完整阅读 `codex.md`、Java 编码规范、规划编排与 Benchmark 技能。
- 尚未修改 RAG 业务代码、数据库或远端业务数据；下一步执行阶段 0 现状审计。

### 2026-07-18 阶段 0 审计结果

#### 工程与构建基线

- 项目为 Java 17、Spring Boot 3.4.3 的六模块 Maven 工程；根 POM 默认 `skipTests=true`，所有有效测试命令必须显式传入 `-DskipTests=false`。
- 后端在 Temurin 17 下完成六模块编译；共发现 138 个测试，其中 types 模块 12/12 通过，app 模块 126 个测试有 14 个既有错误、0 个 assertion failure。错误由 8 个“无可运行方法”遗留样例、2 个缺少可信租户上下文的聊天测试、3 个依赖运行时 Agent/Tool 配置的测试等构成；总耗时约 31.84 秒。JDK 25 会因旧版 Surefire/Mockito 进一步放大到 48 个错误，因此后续冻结 Java 17。
- 前端 `npm run build` 通过，Vite 构建约 0.907 秒，总命令约 2.88 秒；主入口 JS 为 165.19 kB（gzip 63.61 kB）。当前没有 Vitest、Vue Test Utils、Playwright/Cypress、ESLint 或独立 typecheck 脚本。
- Maven 存在 API 子模块 parent `relativePath/groupId` 警告和旧版 resources/Surefire 警告，记录为已有构建债务，不与 RAG 首批领域实现混在同一个提交。

#### 后端与数据现状

- RAG 只有 `rag_knowledge_base`、`rag_document`、`rag_chunk` 三张占位表以及 PO/DAO/Mapper；没有 `domain/rag`、Controller、DTO、摄取任务、检索服务、模型客户端、Qdrant 客户端或 Kafka RAG Consumer。
- 对线上 MySQL 做只读核验：只有上述三张 RAG 表，均为 0 行，结构与仓库全量 SQL 一致；没有 version/job/profile/binding/retrieval/eval/outbox 表。
- 旧 DAO 的 `queryByKnowledgeBaseId/queryByDocumentId/queryByChunkId`、按知识库/文档列表和 `updateById` 均缺少 tenant 条件；tenant_id 允许 NULL，唯一键也是全局 ID，不能进入新的生产调用链。
- 项目没有 Flyway/Liquibase，既有方式是日期命名的手工 MySQL 8 增量 SQL；`ai-agent-scaffold-platform.sql` 开头包含 DROP TABLE，只能作为全量初始化，禁止用于此次上线。
- RAG-Server 当前 Qdrant collection 为空，三个模型网关健康；Java 配置中尚无 `ai.rag.*`，业务代码没有调用 Docling、Embedding、Reranker 或 Qdrant。
- 现有 `ObjectStorageService/MinioObjectStorageService` 可复用对象存储、SHA-256 和路径安全思想，但 byte[] 全量读写不适合 50 MiB 多并发文档，需要后续增加流式能力或专用 bulkhead。
- Context Kafka 可参考“消息只传任务 ID、MySQL 账本为真相、CAS claim、retry/DLT、上下文清理”；但其 60 秒无心跳租约和数据库/消息双写空窗不能直接复制。RAG 将采用独立 task/outbox，并复用调度模块的 heartbeat/fencing/指数退避模式。

#### Context、Agent 与前端现状

- `ContextContributor`、`ContextFragmentType.RAG`、`ragTokens` 和模型调用前的 `ContextInjectionPlugin` 已存在，可作为强制注入点。
- 当前 Context 请求缺少 question、agent/workflow、runId、revision、deadline 和取消语义；插件对 contributor 异常一律 fail-open，不能满足 `rag_required=true`；贡献结果也没有结构化 citation。
- `/rag` 已注册但 `KnowledgeBaseView.vue` 仅为静态占位；路由只校验登录，没有 owner/admin 管理权限，侧边栏也没有 RAG 入口。
- 附件、聊天、定时任务和 Agent 页面已有逐行 pending/success/error、取消与旧响应防回写模式，可以复用交互思想，但聊天附件 Store 不能直接承担 RAG 文档生命周期。
- 发现移动端 sidebar 与 topbar 双 sticky z-index 覆盖、缺少统一异步按钮/确认框/错误态等既有前端问题；在 RAG 页面实现时一并建立通用交互组件和响应式门禁。

#### 与旧架构文档的差异

- `docs/architecture/2026-07-18-enterprise-rag-architecture.md` 中“Python rag-api/rag-worker 部署服务器”的描述已不符合用户最终决策。本 Target 以“业务编排、摄取和检索全部在本机 Java 项目内，服务器只提供环境中间件”为准，后续更新该文档。
- Qdrant 当前按用户要求公网且无 API Key，这是已知高风险现状。开发/评测阶段 Java 必须强制 tenant payload filter + MySQL 后验校验；在未恢复认证或收窄网络前，不把该部署描述为生产安全。

### 阶段 1A 本轮执行计划：租户安全领域地基

1. 新增可重复执行的 `2026-07-18-rag-module.sql`：在三张空占位表上以增量方式补齐 tenant 非空、revision/generation 字段和联合索引；新增不可变 document version、ingest task、outbox、retrieval profile、agent binding、retrieval record/citation 表。脚本必须有上线前置检查和回滚说明，不能包含 DROP TABLE。
2. 新建 `domain/rag`：知识库、文档版本、摄取任务、profile、binding、evidence/citation 实体；任务状态、阶段、操作、检索模式、失败策略枚举；状态机和管理员权限规则。
3. 新建 `IRagRepository` 及 Docling/Embedding/Reranker/VectorStore/SparseEncoder/事件发布端口；生产方法 tenantId 必须为首个作用域参数，不暴露 PO/MyBatis。
4. 新增 `RagProperties` 配置模型与 `application-*.yml` 环境变量占位，固定 768 维、端点、超时、批次、并发、collection 和 topic；真实密钥不进入 Git。
5. 在 app 测试模块新增领域状态机、owner/admin、跨租户参数、取消、fencing 和配置校验测试；只运行新 RAG 测试、types 测试和六模块 compile，避免用既有 14 个错误掩盖新回归。
6. 完成后向本计划追加文件、SQL、测试数量与真实结果，中文提交“建立RAG领域与数据模型基础”。

### 2026-07-18 阶段 1A 执行结果

#### 代码与结构改动

- 在 `domain/rag` 新增 26 个 Java 文件：知识库、逻辑文档、不可变文档版本、分块、摄取任务、检索策略和 Agent 绑定实体；补齐摄取 operation/status/stage/checkpoint、租约和检索模式等值对象。
- 新建 `IRagRepository`，全部生产方法强制以可信 `tenantId` 为首参；新建 Docling、Embedding、Sparse、Reranker、Qdrant 和摄取事件端口，Domain 不依赖 HTTP、MyBatis、Kafka 或供应商 DTO。
- 摄取任务状态机实现 pending/claim/lease takeover/heartbeat/checkpoint/retry/dead/cancel/complete 与 fencing token。取消请求生效后，`assertExternalCallAllowed` 会立即拒绝新的解析、Embedding 和向量写入副作用。
- 文档解析端口使用受控临时 `Path + contentLength`，未把大文件聚合为 `byte[]`；向量检索端口要求每个知识库携带活动 generation，后续 Qdrant 适配器必须同时下推 tenant、kb 和 generation filter。
- 新增 `RagProperties` 和 `ai.rag.*` 环境变量配置：固定 768 维，分别配置 Qdrant/Embedding/Reranker/Docling 超时、并发和批次。日志摘要只显示密钥是否配置；按用户当前部署决策允许 Qdrant 无 API Key，其他三个模型/解析服务在启用 RAG 时必须配置密钥。
- 新增 MySQL 8 增量迁移，保留三张历史表并增加强租户联合键、活动/目标 generation、活动 version、revision 等字段；新增 document_version、ingest_task、outbox、retrieval_profile、agent_binding、retrieval_record、retrieval_citation 共 7 张表。脚本没有 `DROP TABLE`、`TRUNCATE` 或业务 `DELETE`，包含阻断式前置审计、可重复 DDL、验证和前向兼容回滚说明。

#### 真实验证记录

- Java 17 命令：`mvn -pl ai-agent-scaffold-app -am clean test -DskipTests=false -Dtest=RagKnowledgeBaseAuthorizationServiceTest,RagIngestJobEntityTest,RagPortContractTest,RagPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`。
- 结果：六模块 clean compile 成功；新增 RAG 测试 20/20 通过，0 failure、0 error、0 skipped，总耗时 7.019 秒。覆盖 owner/admin 与跨租户拒绝、Repository tenant 首参、租约接管、fencing、检查点防倒退、取消副作用屏障、重试耗尽、operation/generation 保留、流式解析边界、活动 generation 检索范围和配置/密钥脱敏。
- Types 回归命令：`mvn -pl ai-agent-scaffold-types -DskipTests=false test`；结果 12/12 通过，总耗时 0.641 秒。
- MySQL 版本：远端只读查询为 8.0.46。第一次对最终文件验证尝试在初始通信阶段出现 `ERROR 2013`，迁移尚未开始，核验无临时库遗留；随后重试成功。
- 最终迁移文件 SHA-256：`6aaa9b2700deceb430f7b33294e989d449cd9613bdb743f19d6044fb874c9579`。在独立临时库 `rag_migration_test_20260718224920` 中只创建三张历史 RAG 基线表，连续执行最终迁移两遍均成功；验证结果为 `new_tables=7`、`nullable_tenants=0`、`missing_critical_columns=0`、`tenant_unique_indexes=4`。测试结束已删除临时库，正式 `ai_agent_scaffold` 未执行迁移、未写入业务数据。
- 静态敏感信息扫描未在本阶段新增代码、测试和迁移中发现服务器密码或模型密钥。`application.yml` 只新增环境变量占位；扫描命中的 Nacos 公网地址为工作区已有配置，不属于本阶段引入。

#### 已知边界与下一步

- 当前闭环是领域、配置和数据库结构地基；基础设施 PO/DAO/MyBatis、事务 Outbox、远程客户端和业务 API 尚未实现，不能将其描述为可用 RAG。
- 根工程已有 API parent、旧 resources/Surefire 警告仍存在；全量 app 测试的 14 个既有错误未在本提交顺带修改，新增定向测试全部通过。
- 下一执行切片进入阶段 1B：实现新表持久化模型、强租户 SQL、乐观锁/CAS task claim 与 repository 适配器，并以临时库集成测试证明跨租户查询和并发领取语义。
