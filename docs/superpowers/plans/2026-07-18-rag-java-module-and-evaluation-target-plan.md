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

### 阶段 1B 本轮执行计划：强租户持久化与任务 CAS

1. 以阶段 1A 的最终表结构为唯一依据，升级三张历史 RAG PO/DAO/Mapper，并新增 document_version、ingest_task、retrieval_profile、agent_binding 的 PO/DAO/Mapper；所有读取、更新和逻辑删除 SQL 必须同时带 `tenant_id` 与业务 ID。
2. 实现 `RagRepository` 适配器和集中映射器，完成领域枚举与数据库小写值、checkpoint JSON、metadata JSON、UTC 时间、revision/row_version 的双向转换；未知数据库枚举值必须显式失败，不能静默降级。
3. 任务领取采用单条原子条件更新：只允许到期的 pending/retrying 或过期 running，递增 `attempt_count`、`fencing_token` 和 `row_version`；随后按 tenant/task 读取本次领取结果。普通状态更新必须校验 expected revision，返回 0 交由领域层报告并发冲突。
4. 为 Repository 映射和租户参数传递增加单元测试；在独立临时 MySQL 库执行最终迁移并插入两个租户同名业务 ID，验证同租户唯一、跨租户共存、跨租户查询/更新为 0，以及两个并发连接只能有一个成功领取任务。
5. 运行新增测试、types 回归和六模块 compile；向本计划追加文件、命令、临时库、并发结果、失败与遗留项，完成中文本地提交。正式库仍不执行迁移。

### 2026-07-18 阶段 1B 执行结果

#### 持久化闭环

- 升级知识库、文档、分块三套 PO/DAO/MyBatis XML；新增文档版本、摄取任务、检索策略、Agent/Workflow 目标绑定四套 PO/DAO/XML，共 7 套映射。
- 新增 `RagRepository`、`RagPersistenceMapper`、`RagPersistenceCodec`：领域枚举统一写为小写数据库值，旧文档 `active/indexed/indexing` 状态显式归一化；未知枚举、损坏 metadata/checkpoint JSON、stage/checkpoint 不一致会失败关闭。
- Repository 对实体写入再次核验可信 tenant 与实体 tenant 一致；全部业务 ID 查询、更新、软删除都下推 `tenant_id`。移除 `PlatformRepository` 中旧的无租户 RAG 桥接方法及三项 DAO 依赖，并删除 DAO 无租户兼容入口，使不安全路径在编译期消失。
- 摄取任务 `claimDue` 固定使用 `tenant_id + task_id` 单条原子 UPDATE：只允许 pending、到期 retrying、租约过期 running，要求 attempt 未耗尽，并原子递增 attempt/fencing/row_version。Repository 仅在 UPDATE 影响 1 行后同事务按 tenant+task 回查。
- 普通知识库、文档、版本和任务更新使用 revision/row_version CAS。对领域当前未暴露的 source、解析产物和审计时间列采用“不覆盖”或数据库派生保留，避免状态更新把已有数据写成 NULL。
- 在迁移中进一步补齐 chunk `version_id/parent/previous/next`、知识库默认 profile、文档活动 version、文档版本 chunker/Embedding revision/row_version、任务 cancel reason，以及通用 target type/id/max tokens；最终 SQL SHA-256 为 `e14d1fb4335322ff9b02a822a0e9a3b1e5f2de8034f414f69ffcfd15d2420c49`。

#### Java 与 Mapper 验证

- 最终命令：`mvn -pl ai-agent-scaffold-app -am clean test -DskipTests=false -Dtest=RagKnowledgeBaseAuthorizationServiceTest,RagIngestJobEntityTest,RagPortContractTest,RagPropertiesTest,RagPersistenceCodecTest,RagPersistenceMapperTest,RagRepositoryTest,MyBatisMapperLoadTest -Dsurefire.failIfNoSpecifiedTests=false`。
- 结果：六模块 clean compile 成功；30/30 个定向测试通过，0 failure、0 error、0 skipped，总耗时 7.592 秒。新增覆盖 PO 核心字段往返、旧状态归一化、未知值拒绝、可信 tenant 传参、跨租户实体写入拒绝、claim 输赢分支，以及 7 个 Mapper 的实际 MyBatis 加载和 BoundSql tenant/CAS 条件。
- Types 回归：`mvn -pl ai-agent-scaffold-types -DskipTests=false test`，12/12 通过，总耗时 0.531 秒。
- 7 个 RAG XML 均通过 `xmllint --noout`。相关源码未出现无 tenant 的 `queryById/queryByKnowledgeBaseId/queryByDocumentId/queryByChunkId` DAO 入口。

#### 临时 MySQL 8 集成与并发结果

- 第一次集成尝试在迁移期间出现远端 SSL EOF（`ERROR 2026`），未采信任何业务结论并清理临时库。禁用客户端 SSL 后，最终迁移连续执行两遍成功、同租户重复知识库键被拒绝；随后汇总查询发生一次 `ERROR 2013`，因此只保留已明确返回的两项结果并再次清理临时库。
- 最终将查询拆短，在临时库 `rag_cas_test_20260718231340` 重新执行最终迁移和真实数据测试，结果：两个租户可同时保存相同 `kb_id` 和 `chunk_id`，行数均为 2；tenant-x 查询为 0、tenant-x 更新影响 0 行。
- 两个独立 MySQL 客户端同时领取同一个 pending task：worker-b 影响 1 行、worker-a 影响 0 行；最终 `attempt_count=1`、`fencing_token=1`、`row_version=1`、owner=worker-b，证明单次竞争只有一个赢家。测试临时库已删除，正式库未迁移、未写业务数据。

#### 遗留边界

- 本阶段尚未实现 Outbox 发布器、Kafka 消费者、摄取用例、MinIO 流式暂存和外部中间件客户端；这些进入阶段 2。
- MySQL 8 对批量 upsert 的 `VALUES(column)` 仍兼容但已提示未来弃用；后续性能阶段需评估改为 alias 语法，不能在没有基准结果时声称有性能改善。
- Mockito 在 Java 17 测试中提示动态 agent 未来将收紧，这是既有测试工具链升级项，不影响本轮 30 个测试结论。

### 阶段 2A 本轮执行计划：上传事务、Outbox 与管理 API

1. 只读核验现有 JWT/TenantContext、Controller 响应规范、Multipart 限制、MinIO 对象命名与 Kafka 发布配置；确定管理员角色和值来源，不复制浏览器传入的 tenant/user/role。
2. 为 RAG 增加流式对象存储端口和 MinIO 实现：仅允许 PDF、DOCX、Markdown，校验扩展名、MIME、magic bytes、文件名、单文件大小和空文件；以 tenant/kb/document/version 的服务端路径保存并流式计算 SHA-256，失败时清理半成品。
3. 扩展 Repository 和 MyBatis，新增 Outbox PO/DAO/XML；在一个本地数据库事务中创建逻辑文档、不可变版本、pending ingest task 与 pending outbox，幂等键冲突返回已存在任务，不重复上传或投递。
4. 实现知识库创建/列表、文档上传/列表、任务详情、取消请求 API；所有管理入口调用 owner/admin 授权服务，状态 DTO 不返回 object key、密钥、内部 lease owner 或未脱敏错误。
5. 实现 Outbox 轮询发布器和 Kafka 消息契约，消息只含 tenantId/taskId/eventId/schemaVersion；发布确认后标记 published，失败按租约、fencing 和退避重试，应用退出/重复投递不丢任务。
6. 用纯单元测试覆盖格式伪装、超限、路径安全、事务幂等、跨租户、取消屏障和 Outbox 状态；用临时 MySQL 验证同事务写入/回滚和 Outbox CAS，用 Kafka 测试条件允许则做集成，否则保留可复现的未完成证据。完成后追加真实结果并中文提交。

### 2026-07-18 阶段 2A 阶段性执行结果（一）：安全上传、事务登记与知识库管理

#### 已完成代码

- 对象存储新增 `Path + size` 流式写入命令和 `putFile`：MinIO 使用已知长度输入流，开发本地存储使用同目录临时文件、流式 SHA-256 和原子替换；新增独立 `ai-agent-rag` 桶配置。Multipart 明确落盘阈值为 0，单文件 50 MiB、请求 52 MiB，避免文档整体进入 JVM 堆。
- 上传策略支持 PDF、DOCX、Markdown：安全文件名、扩展名/MIME、声明/实际长度、PDF magic、Markdown 严格 UTF-8/NUL 校验；DOCX 使用 `ZipFile` 验证真实 OOXML 必要条目，并限制 4096 entries、单 entry 32 MiB、总声明解压 100 MiB，拒绝重复条目、未知大小、路径穿越和伪 ZIP。
- 新增 `RagDocumentUploadService`、`RagUploadRegistrationPort` 和 MySQL 适配器：对象上传与慢 I/O 不包进数据库事务；数据库事务以 ingest task 唯一幂等键为第一道闸门，再原子写 document、document_version 与 outbox。DB 失败删除本次唯一对象；并发输家删除自己的对象并返回已存在任务。
- 新增 Outbox PO/DAO/MyBatis：tenant+event/task 查询、attempt/maxAttempts、lease、heartbeat、fencing、rowVersion、claim、续租、published/retrying/dead CAS 均已落地。消息载荷只登记 schemaVersion/eventId/tenantId/taskId，不包含对象 Key、正文或密钥。
- 新增知识库 owner/admin 创建和租户成员列表 API；身份只从 `TenantContextHolder` 获取。ID、768 维和 collection alias 由服务端生成，alias 使用 tenant SHA-256 前缀。新增 `(tenant_id,kb_name)` 唯一键，预检与数据库并发冲突均映射稳定错误码。
- 迁移 `rag_outbox` 增加 `max_attempts/heartbeat_at/row_version`，关键列校验增至 28；迁移移除硬编码 `USE ai_agent_scaffold`，以后执行方必须显式选择数据库。

#### 真实测试和环境留痕

- Java 17 上传组合：`RagUploadFilePolicyTest,RagDocumentUploadServiceTest,MinioObjectStorageServiceTest` 共 25/25 通过，0 failure、0 error、0 skipped，总耗时 7.560 秒。其中格式安全 15、上传编排 4、对象存储 6。
- Java 17 知识库组合：授权、管理服务、Controller、重复键映射共 9/9 通过，0 failure、0 error。
- Outbox 基础设施六模块编译成功，XML 通过 `xmllint`，MyBatis `XMLMapperBuilder` 实际载入 9 个 statement 成功。
- 最终迁移 SHA-256：`35f706870ad68a41ccadb971cb869a718a9f1525a959d2b2a3b6a69394735a58`。显式选中的独立临时库连续执行两遍成功：`missing_critical_columns=0`、租户数据问题均为 0、outbox 24 列、默认 maxAttempts/attempt/fence/revision=`10/0/0/0`；同租户同名第二次插入被唯一键拒绝，最终仅 1 行。临时库已删除。
- 一次使用默认 Java 25 的对象存储测试因当前 Byte Buddy 只正式支持到 Java 24，3 个 MinIO mock 初始化报错；切换项目规定的 Temurin Java 17 后同组 6/6 通过，该失败属于测试运行时版本不匹配，未计为业务通过。
- 一次临时库验证命令虽然在客户端选择了临时库，但当时迁移文件仍含硬编码 `USE ai_agent_scaffold`，导致 SQL 实际切换并幂等执行到了正式开发库；该次输出因此不作为临时库证据。未写入业务数据，但 DDL 已在正式开发库补上本阶段 Outbox 列。发现后立即移除脚本中的 `USE` 并用显式临时库重新验证；没有执行破坏性回滚。此失误必须保留在发布审计中。

#### 尚未闭环

- 本次是阶段性提交；文档上传 HTTP、任务查询/取消、Outbox 轮询发布器和真实 Kafka CAS 集成仍在阶段 2A 后续切片，不能把当前状态描述为完整可用 RAG。
