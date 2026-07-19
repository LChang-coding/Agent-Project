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

### 阶段 2A 第二切片执行计划：文档 API、取消与可靠发布

1. 增加文档上传/列表和摄取任务详情 DTO/Controller；Multipart 只转存到受控临时 Path，finally 删除，不调用 `getBytes()`；响应不暴露 source bucket/object key、lease owner、fencing token 或内部错误堆栈。
2. 增加任务取消管理用例：可信 owner/admin 鉴权并校验 task->kb 同租户归属；pending/retrying 在本地事务内经过 cancel_requested 后落 cancelled，running 只写 cancel_requested 作为 Worker 外部调用前屏障；所有更新使用 revision CAS。
3. 增加 Outbox 扫描候选查询和轮询发布器：短事务领取、事务外等待 Kafka ack、随后 fencing CAS 确认；失败指数退避并截断脱敏错误，耗尽进入 dead；关闭开关时不启动调度。
4. 增加 Controller、取消状态、Outbox 发布成功/失败/旧栅栏测试；在临时 MySQL 做 document/version/task/outbox 同事务成功与故障回滚、并发 claim/fencing 验证；Kafka 若无法建立可控测试环境，使用 mock ack 单测并明确不冒充真实 broker 集成。
5. 完成后将全部命令、接口、线程/轮询参数、失败与未完成项追加到本计划，执行中文本地提交。

### 2026-07-18 阶段 2A 第二切片执行结果

#### API 与取消闭环

- 新增 `POST /api/v1/rag/knowledge-bases/{knowledgeBaseId}/documents`、同路径 GET 列表、`GET /api/v1/rag/ingest-tasks/{taskId}` 和 `POST /api/v1/rag/ingest-tasks/{taskId}/cancel`。
- HTTP 层只使用可信 `TenantContextHolder`。Multipart 通过 `transferTo(Path)` 落入系统临时文件，领域校验和对象存储结束后在 `finally` 删除；没有调用 `MultipartFile.getBytes()`。响应不含 bucket/objectKey、leaseOwner、leaseUntil、fencingToken、checkpoint JSON 或内部错误 message。
- 新增文档/任务管理员用例。pending/retrying 或无租约 cancel_requested 使用两次 revision CAS 依次记录取消请求和 cancelled 终态；running 只写 cancel_requested 并保留当前租约，后续 Worker 的 `assertExternalCallAllowed` 会阻断解析、Embedding 和向量写入等新副作用。

#### Outbox 发布闭环

- 全局 due 扫描是唯一无 tenant 入参的 Outbox 查询，并且只投影 tenantId/eventId；claim、读回、published/retrying/dead 均使用 tenant+event，终态变更还要求 leaseOwner+fencingToken。
- `RagOutboxClaimService` 在短事务内原子领取并读回新 fence；Kafka send 和 ACK 等待在事务外。只有明确 ACK 才 CAS 标记 published；异常只落 `KAFKA_PUBLISH_FAILED:<异常类型>`，不保存可能含密钥的原始 message。
- 重试采用指数退避、上限和可配置抖动；attempt 耗尽进入 dead。配置：默认关闭、poll 1000ms、batch 20、lease 30000ms、ACK timeout 10000ms、retry 1000~300000ms、jitter 0.2。发布器使用条件 Bean，关闭时不创建调度线程。
- 本轮 Kafka 测试使用 mock `KafkaTemplate` 和真实 Spring Kafka 3.3.3 Future API，没有连接真实 broker；因此不能宣称验证了网络断连、broker 重启或真实 ACK 延迟。

#### 真实验证结果

- Java 17 最终组合命令包含知识库、上传、Controller、取消、Outbox、配置、MyBatis 和对象存储共 13 个测试类；结果 52/52 通过，0 failure、0 error、0 skipped，六模块 BUILD SUCCESS，总耗时 7.838 秒。
- 临时 MySQL 四表事务测试：成功事务提交后 task/document/version/outbox 计数为 `1/1/1/1`；第二个事务先写 task/document/version，再故意触发 outbox `(tenant,event)` 重复键，客户端确认出现 `Duplicate entry` 后执行 ROLLBACK，四类 rollback 业务 ID 计数均为 `0/0/0/0`。临时库已删除。
- 临时 MySQL Outbox 并发领取：两个独立客户端同时 claim 同一 pending event，worker-a 影响 0 行、worker-b 影响 1 行；最终 attempt/fence/rowVersion=`1/1/1`。旧 owner+旧 fence 的 published CAS 影响 0 行，赢家 CAS 影响 1 行，最终 published 且 rowVersion=2。临时库已删除。
- XML、组合源码 `git diff --check` 通过。正式开发库在本切片没有新增业务数据操作。

#### 下一步

- 阶段 2A 管理/投递入口已闭环；真正解析、切块、Embedding、Qdrant 写入、任务心跳/checkpoint/取消清理和激活索引仍属于阶段 2B Worker，当前上传的任务不会在 Outbox/Worker 开关关闭时自动完成。

### 阶段 2B 执行计划：摄取 Worker、模型客户端与索引激活

1. 只读探测已部署 Docling、Embedding、Reranker、Qdrant 的版本、健康和真实 API 契约；只使用官方文档与服务自描述结果，不凭记忆硬编码。探测不上传项目源码、不写服务器配置，密钥和服务器密码不进入日志/提交。
2. 增加对象存储流式下载到受控 Path；实现 Docling 文件解析适配器、Markdown 本地解析、结构化分块器和稳定内容哈希。PDF/DOCX 的远程请求使用文件流，解析结果限制响应体、页数/字符数和超时。
3. 实现 TEI Dense Embedding、纯 Java 稳定 Sparse 编码、Qdrant collection/point upsert/delete/search 和 Reranker 客户端；统一限流、超时、响应上限、维度检查、租户+kb+version/generation payload，日志不含正文/向量/密钥。
4. 实现 Kafka ingest consumer 与 Worker：按 tenant+task 领取租约；每次 Docling、Embedding、Qdrant 外部副作用前重新读取任务并执行取消/fencing 屏障；分批推进 checkpoint、心跳续租、可重试/不可重试错误；完成验证后用 CAS 激活 document/version/knowledge-base，再清理旧版本。
5. 增加纯单元/协议契约测试，并针对公网中间件使用不含项目源码的合成小样本做真实 smoke test；记录接口、文件格式、批次、线程、耗时和失败。临时 MySQL 验证 checkpoint/取消/fencing/激活；阶段闭环后追加结果并中文提交。

### 2026-07-18 阶段 2B 阶段性执行结果（一）：流式下载、Chunker 与 Sparse

- 对象存储新增受控流式下载：MinIO 和本地对象均使用 8 KiB buffer，不进入整文件 `byte[]`；实时 maxBytes、SHA-256、同目录临时文件和原子发布；失败删除半成品并保留原目标，拒绝绝对/越界路径和符号链接根、父目录、目标。
- 新增结构化 Parent/Child Chunker：识别标题、段落、列表、Markdown 表格、fenced code；字符+近似 Token 双预算、超长 Unicode 安全拆分、受控 overlap、父子/前后邻接、稳定 SHA-256 chunk ID/content hash 和版本 metadata。
- 新增确定性 Sparse 编码：NFKC+小写，Unicode 英数词和中日韩单字/双字，词表 revision 参与 64-bit FNV-1a 索引；冲突累加 log-TF，L2 归一化并按 index 稳定排序。该实现是真实稀疏词项向量，不使用 Dense 冒充，但没有 corpus IDF，后续消融必须单独标识为 hashing log-TF sparse。
- 新增 version/document/knowledge-base 激活规则：版本必须 processing 后才能 ready；文档只能激活匹配 target generation；知识库 generation 不允许倒退。
- Java 17 组合验证 32/32 通过，0 failure/error：Sparse 5、Chunker 6、激活 3、配置 7、对象存储 11，总耗时 7.326 秒。Chunker/Sparse 另有 100 组随机预算边界；默认 Java 25 仍受既有 Byte Buddy 版本限制，规定运行时 Java 17 下通过。
- 本小节仅是可独立复用的本地处理基础，远程客户端与 Worker 尚未在此提交中宣称完成。

### 2026-07-19 阶段 2B 真实中间件契约探测留痕

- 探测只使用无敏感合成文本和最小 PDF/DOCX；没有上传项目源码或业务文档，没有修改服务器配置，临时文件已清理，密钥未写入日志和仓库。
- Docling Serve 1.26.0 真实公网契约为 `POST :5001/v1/convert/file`，`multipart/form-data`，认证头 `X-Api-Key`；唯一必填字段是 binary array `files`。默认 `to_formats=[md]`，200 JSON 的有效正文位于 `document.md_content`，其他格式字段可为 null。服务默认 `do_ocr=true`，与首期设计相反，Java 必须显式发送 `do_ocr=false`、`force_ocr=false`、`include_images=false`、`include_page_images=false`和受控 `document_timeout`。
- Docling 合成 DOCX 首次总耗时 8.884315 秒，热请求 2.196345 秒，服务内 processing_time 分别为 0.235355/0.029989 秒；显式关闭 OCR 的请求 200，总耗时 8.885362 秒。最小一页 PDF 总耗时 12.242203 秒，服务 processing_time 11.459224 秒。发生过一次约 5.016 秒的网关空连接，容器一直健康且同请求一次重试后 200；客户端只对连接类异常做有界重试，业务幂等仍以 ingest task 为真相源。
- Embedding 契约为 `POST :8081/embed`，Bearer，请求 `{inputs:string[]}`；真实 2 条合成输入响应 200，耗时 0.981055 秒、响应 18994 bytes，得到 2×768 维 L2 归一向量。固定 multilingual-e5-base revision `d128750597153bb5987e10b1c3493a34e5a4502a`，query/passage 必须使用对应前缀；服务最大输入 512，并发 8，client batch 16。
- Reranker 契约为 `POST :8082/rerank`，Bearer，字段为 `query/texts/return_text/raw_scores`；真实 3 候选请求 200，耗时 2.360528 秒、响应 268 bytes，顶层数组项为 `index/text/score`。固定 bge-reranker-base revision `2cfc18c9415c912f9d8155881c133215df768a70`，client batch 16。单个合成语义样例的直观排序并不可靠，后续只以公开基准集指标判断 rerank 增益。
- Qdrant 版本 1.18.2，匿名公网 `/healthz` 和 `/readyz` 均 200，`/collections` 当时为空；本次只读阶段未创建 collection 或 point，因此不把 Qdrant upsert/query 写成“真实服务已通过”。
- 服务器探测结束时 7 个容器均 running，已定义健康检查的容器均 healthy，Prometheus 6 个 target 均 up。内存占用：Embedding 1.863 GiB、Reranker 1.867 GiB、Docling 1.367 GiB、Qdrant 46.55 MiB；主机基线 5.1/15 GiB，Swap 0/2 GiB，根盘 14/39 GiB。这些是单次功能探测快照，不是压测结果。

### 2026-07-19 阶段 2B 阶段性执行结果（二）：TEI 与 Qdrant 客户端

- 新增 TEI Embedding/Reranker Java HTTP 适配器：请求前批次和文本上限校验、公平并发槽位、连接/请求超时、有界响应、非 200 不回显错误体或密钥。Embedding 显式添加 E5 `query: `/`passage: ` 前缀并严格校验数量、768 维和有限数；Reranker 按真实 `index/score` 映射 chunk，拒绝缺字段、重复/越界索引和非有限分数。审查发现并修复了 `index/score` 原用基本类型、缺字段会被 Jackson 静默补成 0 的缺陷。
- 新增 Qdrant 1.18.2 REST 适配器：`dense` 768/Cosine + `sparse` named vector 严格 schema 校验与幂等创建；`wait=true` 分批 upsert；非 UUID 业务 ID 稳定映射 UUID 并在 payload 保留原 ID；可信 tenant/kb/document/version/generation/chunk 字段强制覆盖调用方伪造值。delete/count 强制 tenant+version，count 使用 `exact=true`。Dense/Sparse/Hybrid Query API 支持双 prefetch + RRF，同时下推 `tenant AND (kb,activeGeneration)` 并对响应逐条后验，越界命中失败关闭。
- Java 17 组合命令：`mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest=QdrantVectorStoreAdapterTest,TeiModelAdapterProtocolTest,RagPortContractTest,RagPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`；结果 23/23 通过，0 failure/error，六模块 BUILD SUCCESS，总耗时 7.530 秒。其中 TEI 本地 HttpServer 黑盒协议 8 项，Qdrant 协议 5 项，配置 7 项，端口契约 3 项。
- 真实 Qdrant smoke 使用项目预定 collection `ai_agent_rag_e5_v1` 和一个可识别合成 point，无业务数据、单线程、批次 1。初始 GET 404/4.982752 秒，创建 200/3.770338 秒，upsert 200/2.205275 秒，精确 count 200/0.219369 秒且 count=1，hybrid RRF query 200/0.745412 秒且命中 1 条正确 tenant，其他 tenant 同向量 query 200/0.624840 秒且命中 0。tenant+version 删除 200/0.164220 秒，删除后 exact count 200/0.834627 秒且 count=0。collection 保留供项目使用，合成 point 已清理。
- 上述 Qdrant 是功能 smoke，不是延迟基准；首次创建、公网往返和网关都混在单次数值中，禁止将它们当作 P50/P95 或吞吐量结论。真实断网、超限响应和分布式并发创建 409 仍待后续性能/故障评测。

### 2026-07-19 阶段 2B 阶段性执行结果（三）：Docling 流式解析客户端

- 新增 `DoclingDocumentParserAdapter`：Markdown 严格 UTF-8、8 KiB 字符缓冲、有界本地读取，规范化 BOM/换行并按 1~6 级标题产生章节，全程不访问 Docling。PDF/DOCX 用 `BodyPublishers.concat + ofFile` 构造已知 Content-Length 的 multipart，文件不聚合为 `byte[]`。
- Docling 请求发往 `{endpoint}/convert/file`，使用 `X-Api-Key`，显式传 `target_type=inbody`、`from_formats=pdf|docx`、`to_formats=md`、`do_ocr=<命令>`、`force_ocr=false`、`include_images=false`、`include_page_images=false`、两个 `page_range=1|maxPages` 表单值和受控 `document_timeout`；因此 OCR、页数与超时不再依赖服务默认。
- 客户端只对 `HttpClient.send` 的 `IOException` 最多重试一次；Interrupted、HTTP 非 200、响应超限、非法 JSON、空 Markdown 和非 success 状态都不重试且失败关闭。不保留远程错误体或 Jackson 原始异常文本，避免正文/密钥进入日志。
- Java 17 最终协议组合命令：`mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest=DoclingDocumentParserAdapterProtocolTest,TeiModelAdapterProtocolTest,QdrantVectorStoreAdapterTest,RagPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`；结果 28/28 通过，0 failure/error，六模块 BUILD SUCCESS，总耗时 7.470 秒。Docling 本地 HttpServer 黑盒测试 8 项，覆盖 Markdown 不出网、multipart 真实文件字节/全部显式字段、一次 I/O 重试、不重试故障、响应上限和错误脱敏。
- 本切片没有访问或修改真实服务器，真实 Docling 合成 PDF/DOCX 的契约数据来自上一节只读探测。新增的重复 `page_range` multipart 序列化已在本地协议测试验证，尚需在 Worker 端到端 smoke 中验证真实网关接收语义。

### 阶段 2B Worker 执行切片计划：从任务领取到索引激活

1. 只读对齐现有 task/document/version/chunk/knowledge-base Mapper 与事务边界，补充全局到期任务候选（只投影 tenantId+jobId）、带 fencing 的心跳/检查点 CAS、分块批量幂等写入和文档/版本/知识库原子激活。
2. 实现单 Worker 摄取编排：原子 claim、受控临时目录、MinIO 流式下载与 size/SHA 复核、Docling/本地 Markdown 解析、Parent/Child 分块、MySQL 存块、child 分批 Embedding+Sparse+Qdrant upsert、精确 count 验证和激活。
3. 每一次 Docling、Embedding、Qdrant upsert/delete/count 前都从 MySQL 重读任务，校验 status、lease owner、leaseUntil 和 fencing token；每批后以 revision CAS 推进单调 checkpoint 并续租。取消时先阻断新的外部副作用，再按 tenant+version 删除 Qdrant 点和 MySQL chunk，关闭 version/document/task，不激活半成品。
4. Kafka listener 只作“有任务可抢”的唤醒，消息仍只含标识符；另有条件开启的短间隔数据库扫描，保证 Kafka 重复/丢失、应用重启和 retrying 任务仍可恢复。首期执行线程固定为 1，进程内去重避免同任务重入。
5. 用内存仓储/伪端口单测覆盖完整成功、每个外部调用前取消、旧 fencing、下载摘要不符、分批检查点、重试/终止错误、索引计数不符和激活事务回滚；再用临时 MySQL 验证 CAS/激活，有条件时做一份无敏感 Markdown 的真实端到端。
6. 本切片完成后追加文件、状态轨迹、配置、线程/批次、命令、测试数、真实耗时、失败与未完成边界，通过后中文本地提交。

### 2026-07-19 阶段 2B Worker 执行切片结果（一）：任务、租约与摄取主链

- 持久化新增全局 due 最小投影，仅返回 tenantId+jobId；普通 claim 与 `cancel_requested` 过期租约清理 claim 分离。取消清理接管保持取消态、递增 fence/revision 且不消耗 attempt，解决原 Worker 宕机后取消任务永久卡住的问题。
- Worker checkpoint/状态更新的 SQL `WHERE` 强制 tenant+task+revision+leaseOwner+fence+未过期租约；心跳使用独立 SQL，只更新 heartbeat/leaseUntil，不读写 row_version，因此不与 checkpoint CAS 争抢。每一次 MinIO/Docling/Embedding/Qdrant 前后都回查 MySQL 并验证实时取消、owner、fence 和租约。
- 新增 complete/cancel/fail 三类本地事务：complete 依次 CAS version READY、document activeVersion/generation、knowledge-base generation、task COMPLETED；cancel/fail 关闭未激活 version、清 targetGeneration、保留旧 activeVersion，再 fenced 关闭 task。任一 CAS 不为 1 抛 `RAG_LIFECYCLE_CONFLICT` 并整个回滚。
- `RagIngestWorker` 实现 INGEST 主链：不可变 scope/generation 校验→version PROCESSING→每 attempt 独立 0700 工作目录→流式下载并复核 size/SHA→解析→确定性 Parent/Child chunk 快照→仅 child 分批 Dense+Sparse→单 Qdrant 批次幂等 upsert→checkpoint→Qdrant exact count + MySQL child count→VERIFYING→原子激活。崩溃恢复从 MySQL chunk 重做未确认批次，pointId 稳定，不持久中间向量。
- 检查点增加 `vectorUpsertIndex<=totalChunks` 和 VERIFYING 必须 `total>0 && processed=vector=total` 不变式；`complete()` 再做一次完整性校验。Qdrant 或 chunk 计数不符禁止激活并清理半成品。终止失败清理本身失败时不提前写 FAILED/DEAD，而转入可过期接管的内部清理态，清理成功后再恢复目标终态。
- 新增 Kafka+DB 双唤醒调度器：Kafka 严格解析 schemaVersion/tenantId/taskId，只作唤醒；默认 2000 ms 数据库扫描补偿 Kafka 丢失/重复、应用重启、retrying 到期、running 租约过期和 cancel cleanup。首期固定 1 个 `rag-ingest-worker-1` 执行线程，有界队列和进程内 tenant+job 去重；总开关默认关闭，关闭时不创建 Worker/扫描/心跳线程。
- 资源默认：scan batch 10，lease 180000 ms，heartbeat 30000 ms，Embedding batch 16，Qdrant batch 64，实际 Worker 批次取两者最小值 16；child 1800 chars/420 近似 tokens，parent 6000/1400，overlap 160。这些配置受 Bean Validation 联合约束并可由环境变量覆盖。

#### Java 真实验证

- Java 17 组合命令：`mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest=RagIngestWorkerTest,RagIngestJobEntityTest,RagRepositoryTest,RagPropertiesTest,MyBatisMapperLoadTest -Dsurefire.failIfNoSpecifiedTests=false`；结果 34/34 通过，0 failure/error，六模块 BUILD SUCCESS，总耗时 7.870 秒。
- Worker 6 项包含：完整 Markdown 到单次原子 complete；Embedding 前 DB 取消时 Embedding/Qdrant upsert 均 0 调用并清理 CANCELLED；Qdrant count 不符不 complete，清理向量/chunk 并 FAILED；heartbeat 发现新 fence 后下载、解析、Embedding、upsert、cleanup、complete 全部 0 调用；错误脱敏/真实对象下载错误可重试；工作目录不跟随 symlink，外部文件保留，普通根和断链根入口均删除。
- 持久化/状态机/配置/Mapper 28 项全部通过。一次编译在 Worker 初版把 Sparse 单文本直接传给批量端口时被 Java 类型系统拒绝；已改为整批 `SparseEncodingCommand` 并与 Dense 同批对齐，修正后上述 34 项全绿。
- 审查发现并修复两项真实缺陷：错误分类器原未包含对象存储实际错误码 `OBJECT_STORAGE_DOWNLOAD_FAILED`，会误判终止；workspace 原 `Files.exists(root)` 会跟随链接，根被替换为断链 symlink 时可能遗留，现使用 `NOFOLLOW_LINKS`。

#### 当前边界

- 本 Worker 明确只执行 `INGEST`。现有检索是 knowledge-base generation 过滤，单文档 REBUILD 直接推进全库 generation 会隐藏其他文档，因此未在语义没有重新设计前冒充实现；DELETE 也尚未开放 API。
- 本节的 Worker 端到端是内存仓储+伪外部端口，尚未使用真实 MySQL+MinIO+Embedding+Qdrant 完成一份文档的全链路。Kafka 唤醒也尚未用真实 broker 断线/重启验证；不将单元测试写成真实 E2E。

### 阶段 3A 执行计划：强租户检索、融合重排与可引用上下文

1. 只读对齐现有 Agent 调用链、上下文装配点、`rag_agent_binding`/`rag_retrieval_profile` 持久化和 Qdrant 查询契约；先明确可信 tenant/user/agent 来源、绑定优先级和无绑定降级行为，禁止由请求体传入 tenant 或绕过 Agent 授权。
2. 建立检索领域模型与应用服务：查询规范化和长度上限、Agent 到知识库绑定解析、知识库 READY/activeGeneration 快照、Profile 参数校验；每次查询只使用同一份不可变 scope，避免检索过程中 generation 漂移。
3. 实现 Dense、Sparse、Hybrid 三种可消融路径。Hybrid 使用 Qdrant 双路 prefetch + RRF；对召回结果做 tenant/kb/generation 后验校验、chunk 去重、相邻 child/parent 上下文扩展和总 Token/字符预算，任何越权命中失败关闭而非静默返回。
4. 实现可选 Rerank：只对有界候选调用，校验返回索引和分数；超时/服务异常按 Profile 的明确策略降级到融合排序，并记录不含正文的阶段耗时与降级原因。最终输出稳定引用 ID、文档名/版本/章节/页码/分块标识和分数，不暴露对象 Key、向量或内部租约字段。
5. 将检索结果接入现有 Agent 上下文管理入口，并提供管理员检索调试 API；上下文标记为 RAG 片段，携带来源元数据和预算统计，使后续上下文压缩、Token 统计和会话持久化能识别，模型提示明确“引用资料不等于系统指令”。
6. 先以伪端口和本地 HTTP 协议测试覆盖无绑定、跨租户、generation 快照、三种模式、RRF、Rerank/降级、预算与引用；再做无敏感合成语料的真实 Qdrant+Embedding+Reranker smoke。把接口、批次、线程、候选数、耗时和边界追加到本文，通过后中文本地提交。

### 2026-07-19 阶段 3A 阶段性执行结果（一）：检索领域主链与 Agent 上下文注入

- 新增可信 `RagRetrievalRequest` 和可评测 `RagRetrievalResult`。请求强制 tenant/user/targetType/targetId/query/预算，结果包含稳定 citation、文档/版本/generation/页码/标题、Dense/Sparse/Fusion/Rerank 分数、降级原因和各阶段候选数/耗时，不暴露对象 Key、向量或凭证。
- `RagRetrievalService` 按 Agent/Workflow 绑定解析可检索知识库与 profile，并在查询开始固定 activeGeneration。跨多个绑定的 Query Embedding 和本地 Sparse 编码各最多执行一次；Dense-only、Sparse-only、Hybrid 三条路径保持独立，Hybrid 在 Java 侧分别取两路候选后执行可复现的 RRF 或归一化加权融合，便于后续做真实消融而不是把 Qdrant 内部融合当黑盒。
- Qdrant 命中回到 MySQL 后按 tenant/kb/document/version/generation/chunk 全字段复核；Dense/Sparse 同 chunk 的范围也必须一致。任何 scope violation 即使来自 optional 绑定也失败关闭。普通 optional 中间件故障可跳过该绑定，required 绑定不可用或其所需模型故障会阻断模型调用；Rerank 故障按明确规则回退融合排序并记录 `rerank_fallback:<profile>`。
- 分块读取增加 tenant+chunkIds 批量接口并限制 500 条；最终引用按 content hash 去重，可加载同版本 parent 和配置数量的前后邻接块，随后同时执行全局 RAG 预算、绑定 maxTokens 和 profile maxContextTokens，预算不足时丢弃整个引用而不截断结构。
- `RagContextContributor` 已替换空占位 Contributor。ChatService 向 ADK state 写入可信 target type/id、本轮真实问题和 runId；ContextInjectionPlugin 将它们交给统一 Context Manager，RAG 仍受 `AI_CONTEXT_RAG_TOKENS` 总预算控制。引用正文做 XML 转义并标记 `untrusted_reference`，明确资料中的角色、命令、工具要求不具备指令权限。required/scope 错误不再被插件通用 catch 吞掉。
- Java 17 最终组合命令：`mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest=RagRetrievalServiceTest,RagContextContributorTest,ContextAssemblerTest,RagRepositoryTest,MyBatisMapperLoadTest,RagPersistenceMapperTest -Dsurefire.failIfNoSpecifiedTests=false`；结果 26/26 通过，0 failure/error/skipped，六模块 BUILD SUCCESS，总耗时 2.052 秒。检索领域 9 项覆盖无绑定零外调、Hybrid+RRF+Rerank、Rerank 降级、required 不可用、optional scope 失败关闭、Token 预算、optional Embedding 降级、Dense-only 和 Sparse-only；上下文贡献 2 项覆盖预算关闭和恶意闭合标签/伪系统指令转义。
- 首次测试编译因测试夹具把 Sparse 的 `Map<Integer,Float>` 索引误写为 Long 被 Java 拒绝；修正夹具后继续。第二次范围测试在 Mockito 覆盖动态桩时求值旧 Answer 导致测试自身 NPE，改用 `doReturn` 后同一越权断言通过。这两次失败均未被计作业务通过。

#### 当前边界

- `query_rewrite_enabled=true` 当前只记录 `query_rewrite_unavailable:<profile>` 降级，尚未接入可信生成模型，因此没有把查询规范化冒充语义改写。
- Hybrid 两路 Qdrant 查询当前顺序执行，以避免为资源紧张服务器直接引入额外并发；真实压测后再决定是否以受控并发换取尾延迟。
- `rag_retrieval_record`/`rag_retrieval_citation` 真实落库、管理员调试 API、绑定/profile 管理 API 和真实 Embedding+Qdrant+Reranker smoke 尚未完成；本节是检索与 Agent 注入的阶段性闭环，不是阶段 3A 全部完成。

### 阶段 3A 第二切片执行计划：检索留痕、策略绑定与调试入口

1. 对齐既有 Controller/DTO/管理员鉴权和 `rag_retrieval_record`/`rag_retrieval_citation` 表结构；补充领域留痕模型、PO/DAO/XML 和单事务写入端口。默认只存 query SHA-256、参数快照、候选数、阶段耗时、错误码和引用元数据，查询正文/引用正文默认关闭并可配置保留策略。
2. 调整检索服务为每次调用在 success/empty/failed 都生成唯一 retrievalId 并尽力写入脱敏留痕；required/scope 等原始业务异常必须保持原错误返回，审计写入失败不得覆盖检索结果，但需产生可定位日志。引用与主记录必须同事务成功或回滚。
3. 增加租户 owner/admin 的 retrieval profile 创建/更新/列表、Agent/Workflow 绑定创建/删除/列表用例和 API；所有 tenant/user/role 来自 `TenantContextHolder`，ID 由服务端生成，revision CAS 防止覆盖并发修改，删除采用软删除。
4. 增加管理员检索调试 API：请求只能选择当前租户可管理的 Agent/Workflow 目标和问题，不能直接传知识库 scope、generation 或 tenant；响应返回引用、阶段指标、降级原因和 retrievalId，不返回 query 原文留痕、对象 Key、向量或中间件错误体。
5. 用单元测试覆盖 profile 参数/并发 CAS/跨租户、绑定唯一性/删除、三种检索状态留痕、审计失败不覆盖主结果、调试鉴权/响应脱敏；用临时 MySQL 验证记录+引用事务和关键查询索引。把命令、接口、留存开关、测试数和未完成项追加后中文提交。

### 2026-07-19 阶段 3A 第二切片阶段性结果（一）：脱敏检索审计

- 新增 `RagRetrievalAuditPort`、审计命令、两类 PO/DAO/MyBatis 和 `RagRetrievalAuditRepository`。主记录和引用由一个 `@Transactional(rollbackFor=Exception.class)` 方法写入；主记录未写成时不会尝试引用，引用批量写失败会抛错触发整笔回滚。
- 检索服务现在对 success、empty、failed 都生成同一个 retrievalId 并尽力留痕；审计异常只记录 tenant/target/retrieval/trace/errorType，不覆盖原检索结果或 required/scope 业务异常。多 profile 不伪装成任一单 profile：单 profile 原 ID，多 profile 使用 `multi_<profile集合SHA-256前24位>`，实际 profileIds、模式和预算保留在 request_snapshot。
- 默认 `AI_RAG_AUDIT_STORE_QUERY_TEXT=false`、`AI_RAG_AUDIT_STORE_CITATION_CONTENT=false`。默认数据库只保存规范化查询 SHA-256、参数快照、候选数、阶段耗时、稳定错误码/异常类型和引用定位元数据；只有两个开关分别显式开启才保存相应正文。
- Java 17 组合命令：`mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest=RagRetrievalServiceTest,RagRetrievalAuditRepositoryTest,RagContextContributorTest,RagPropertiesTest,MyBatisMapperLoadTest -Dsurefire.failIfNoSpecifiedTests=false`；结果 25/25 通过，0 failure/error/skipped，六模块 BUILD SUCCESS，总耗时 3.949 秒。审计仓储 4 项覆盖默认正文关闭、显式正文开启、失败稳定摘要和主记录失败不写引用；检索服务增至 10 项，覆盖 empty/failed 审计与审计失败不覆盖主结果。两个新增 XML 均通过 `xmllint --noout`。
- 临时 MySQL 真事务验证未完成：公网连接返回 `Access denied for user 'root'@当前出口`，本机没有注入 MYSQL/SPRING_DATASOURCE 应用凭证，Docker daemon 也不可用；临时库未创建、正式库未写入。本结果不能被表述为 MySQL 事务集成通过，获得可用受限账号或 SSH 可执行通道后必须补测提交/引用冲突回滚。

#### 本切片剩余项

- Profile/绑定管理 API、管理员检索调试 API 尚未实现；真实 MySQL 事务验证亦未通过，本次只封板脱敏审计的代码和本地契约测试。

### 2026-07-19 阶段 3A 第二切片阶段性结果（二）：检索策略、运行绑定与管理员调试

#### 已完成代码和对外契约

- 新增强租户 retrieval profile 创建、修改、列表持久化与 API：`POST/PUT/GET /api/v1/rag/retrieval-profiles`。更新必须携带 `expectedRevision`，MyBatis 更新使用 tenant+profile+revision CAS；模式、融合策略、TopK、重排候选、邻居窗口、Token 预算、阈值和混合权重均在领域实体内校验，Hybrid 两路权重不允许同时为 0。
- 新增 Agent/Workflow 运行目标的绑定创建、列表与软删除 API：`POST/GET /api/v1/rag/bindings`、`DELETE /api/v1/rag/bindings/{bindingId}?expectedRevision=...`。知识库和 profile 都必须由当前 tenant 查到，绑定预算不能超过 profile 预算，重复键映射稳定冲突码，删除使用 revision CAS。HTTP 层的 `expectedRevision` 改为可选参数后由应用返回稳定 `RAG_BINDING_REVISION_REQUIRED`，避免被 Spring 提前变成通用 400。
- 新增 `POST /api/v1/rag/retrieval-debug`。请求只接收 targetType/targetId/query/maxContextTokens，tenant/user/role/traceId 全部来自可信服务端上下文；不允许请求指定 KB、generation、session/run 或 tenant。只允许 owner/admin，且目标在当前租户至少存在一条有效绑定，否则不调用检索链。空查询和 1~32768 之外预算返回稳定业务错误。
- 调试响应包含 retrievalId、候选数、各阶段耗时、降级原因和最终引用，但不定义 query 留痕、objectKey、向量、密钥或中间件错误体字段。这是租户管理员调试入口，引用正文会返回给该管理员。

#### 真实验证与失败留痕

- Java 17 最终组合命令：`mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest=RagRetrievalConfigurationServiceTest,RagRetrievalConfigurationControllerTest,RagRetrievalDebugServiceTest,RagRetrievalDebugControllerTest,RagRepositoryTest,RagPersistenceMapperTest,MyBatisMapperLoadTest,RagRetrievalServiceTest -Dsurefire.failIfNoSpecifiedTests=false`。结果 40/40 通过，0 failure/error/skipped，六模块 BUILD SUCCESS，Maven 总耗时 2.346 秒。
- 本组覆盖 profile/绑定管理 10 项、调试服务与 Controller 7 项、仓储/映射/MyBatis 13 项和检索主链 10 项。调试用例明确验证非管理员在读绑定前被拒绝、未绑定目标不调检索、可信租户身份传递、默认 4096 Token 预算、空问题稳定错误和响应契约不含内部字段。
- 首轮组合测试因 Controller 测试将 Mockito 的一个 matcher 与七个原始参数混用，导致测试夹具 1 error（当时 39 项中 38 通过）；将全部入参改为明确 matcher 后业务代码未改动即 39/39 通过。随后增加缺失 revision 的稳定错误用例，最终为上述 40/40；首次失败不计入通过数。

#### 当前边界

- 当前“可调试目标”以当前 tenant 中存在有效 RAG binding 为准；绑定创建时尚未与 Agent 注册表/Workflow 表做双向存在性校验，因此旧或手工写入的悬空绑定仍可进入调试服务后再由检索链处理。这项将在前端配置页对齐 Agent/Workflow 可见列表时补强，当前不宣称已完成双向校验。
- 真实 MySQL 审计主表+引用事务回滚仍因无可用受限账号/SSH 可执行通道而未完成；不把 MyBatis 载入测试写成数据库集成通过。

### 阶段 4A 执行计划：租户管理员 RAG 控制台

1. 对齐 Vue 3/Pinia/Axios 现有架构、登录身份和响应契约，增加独立 `rag.ts` 类型化 API，覆盖知识库创建/列表、PDF/DOCX/Markdown 上传、文档/任务查询与取消、profile 配置、Agent/Workflow 绑定和检索调试。所有请求继续走全局 token refresh，前端不传 tenant/user/role。
2. 将当前占位页改为“编辑室式知识运营台”：左侧知识库目录，中间文档生命周期，右侧检索调试/策略配置，沿用项目既有青墨+米金设计变量，通过有节制的纸张质感、精确层级和响应式断点解决当前低复杂度与组件覆盖，不引入新 UI 框架。
3. 每个异步动作都有可见状态：按钮 loading/禁用和动作文案，页内成功/失败通知，skeleton/空态/重试，上传文件名与大小确认，摄取任务阶段进度和取消确认，调试阶段耗时/降级/引用可视化；按钮点击后不再无反馈。
4. 知识库与文档对租户成员可读，创建/上传/取消/profile/绑定/调试仅 owner/admin 暴露可操作控件；后端仍是最终授权边界。绑定目标优先从现有 Agent/Workflow API 选择，如契约不足则先使用有明确标注的 ID 输入，不伪造目标列表。
5. 使用 `vue-tsc --noEmit && vite build` 做类型/生产构建；用本地可控 API 响应或后端可用环境做浏览器点击路径验证，覆盖加载、空态、失败、上传、取消、策略、绑定和调试。没有真实后端/数据库时只宣称构建或模拟交互通过，不冒充 E2E。

### 阶段 4A 补充执行计划：真实上传与取消联调

1. 在仓库保留一份无敏感、可人工核验的 Markdown fixture，记录格式、字节数和 SHA-256；浏览器严格通过文件选择器上传，不用脚本绕过页面交互。
2. 在隔离端口 5174→8092 和隔离 QA 租户中验证上传按钮状态、任务 ID/阶段展示与查询轮询。当前 Worker 默认关闭时，任务应保持待执行而非伪造完成。
3. 对待执行任务发起页面取消并确认最终状态；若实际环境已启用 Worker，则按真实状态记录，绝不为了得到预设结果修改数据库。
4. 完成后记录 fixture、租户/知识库/任务标识、接口链路、实际状态和耗时边界；测试数据保留用于审计，密码不写入文档。

### 阶段 4A 联调缺陷修复计划：无租约取消生命周期一致性

1. 修复真实联调发现的状态分裂：当前 pending 任务被同步标记 cancelled，但 document/version 仍停留 processing/queued。新增单事务无租约取消仓储操作，同时 CAS 关闭 version、清理 document target generation 并关闭 task。
2. 保留运行中任务的取消屏障：持有有效租约时只写 cancel_requested，由 Worker 在每个外部调用前阻断并完成副作用清理；不让 HTTP 线程越权删除 Worker 正在管理的向量。
3. 增加服务与持久化回归测试，覆盖无租约三表原子关闭、并发 revision 冲突回滚契约和有租约只写屏障。
4. 前端依据后端返回的真实 status 展示“已取消”或“取消请求已记录”，任务卡显示状态、阶段和短任务 ID；重新执行真实取消联调并记录结果。

### 阶段 4A 第二次联调缺陷修复计划：取消后的文档派生状态

1. 保留第二份唯一 Markdown 联调样本的失败证据：任务已变为 `cancelled`、未激活版本已关闭，但文档列表仍显示 `processing`，因此本次真实联调不计为通过。
2. 只读定位 `closeTargetGeneration`、文档状态机与列表 DTO 的真实语义，修复聚合根在无活动版本且取消目标代际后仍停留 PROCESSING 的问题；不在前端用 `targetGeneration == null` 猜测或遮蔽后端状态。
3. 增加领域/仓储/应用服务回归断言，要求一次无租约取消事务后 task=CANCELLED、version=CANCELLED、document.targetGeneration=null 且 document.status 不再是 PROCESSING；已有活动版本时不得破坏其可用性。
4. 重新干净构建并启动隔离 8092，使用第三份唯一哈希 Markdown 通过 5174 页面上传和取消，核对任务卡、文档行、0 chunks、通知与控制台错误；按实际结果追加留痕，未通过则继续修复而不是提交。

### 2026-07-19 阶段 4A 执行结果：管理控制台与取消一致性闭环

#### 代码与交互闭环

- 新增类型化 `rag.ts` API，知识库页面从占位页改为租户 RAG 管理控制台，覆盖知识库创建/选择、PDF/DOCX/Markdown 上传、文档和摄取任务查看/刷新/取消、检索 Profile、Agent/Workflow 绑定及管理员检索调试。所有请求沿用全局认证，前端不提交 tenant/user/role。
- 页面提供明确的 loading、禁用、成功/失败通知、空态、文件名/大小确认、任务状态/阶段/短 ID、chunk 与重试信息、取消确认、检索阶段耗时/降级和引用结果；窄屏改为单列并修复导航与内容覆盖。RAG 导航已接入控制台布局。
- 修复运行时装配缺陷：`ChatService` 包声明与目录/引用统一；多个只有单构造器且同时存在测试构造器的 RAG 适配器、Worker 和文档服务显式标记生产构造器，避免 Spring 因多构造器无法选择；新增 Spring 反射装配测试防止回归。
- 无租约取消改为一个事务内 CAS 关闭未激活版本、清理文档 target generation、按是否存在 active version 将文档恢复 READY 或关闭为 FAILED，再关闭任务为 CANCELLED。持有有效租约的运行中任务仍只设置 `cancel_requested`，由 Worker 在外部副作用屏障处接管清理。
- 对历史上已经 CANCELLED 但文档派生状态未关闭的数据增加幂等修复路径。前端取消成功后同时重载任务和文档，避免后端已正确落库但列表保留旧 PROCESSING 快照。

#### 真实浏览器联调留痕

- 隔离环境：前端 `http://127.0.0.1:5174` 代理隔离后端 `http://127.0.0.1:8092`；既有 8091 进程未停止、未替换。使用隔离 QA 租户 `tenant_390f8c04-c571-45da-993d-9d8f8010d1a8`、owner 用户 `user_693ac48a-ca7c-4767-8e6f-3c40970d92b8`、知识库 `RAG QA 知识库 1784396133636`（短 ID `kb_fe56…911b`）。账号密码未写入仓库。
- Fixture 1：`rag-ui-upload-cancel-smoke.md`，677 bytes，SHA-256 `f358ba51d0aa50b31e6f2f08861d7ae85cc90331c2dcbcad6b50c1c953368a7e`；完成真实文件选择上传，并验证相同内容重复上传复用既有任务，不产生重复摄取。
- Fixture 2：`rag-ui-cancel-consistency-smoke.md`，306 bytes，SHA-256 `a14428f67254a687c415b02556e54cb96fff2bfb7e058a74bf1a97b700452c72`；任务 `ragtask…effe` 在 Worker 关闭时为 received、0 chunks。取消后任务和数据库文档派生状态已正确关闭，但页面未自动重载文档，必须手动刷新才从 PROCESSING 显示 FAILED。该轮作为缺陷证据，不计为最终通过。
- Fixture 3：`rag-ui-cancel-refresh-smoke.md`，358 bytes，SHA-256 `3e8226c9a03509dd016aa80c8fcab2057c87fe6078a8f67bcc543c47cc3146e6`；任务 `ragtask…9fb4`，取消前 received、0 chunks、attempt 0/3。通过页面确认取消后等待约 4.2 秒，同一交互内任务显示“已取消 / received / 0 chunks / 尝试 0/3”，文档立即显示“失败”，通知为“摄取任务已取消，未激活的文档版本已关闭”，无需手动刷新。
- 桌面 1440×900 和移动 375×812 均做页面检查；移动端首轮发现横向溢出并修复，刷新后布局正常。最终浏览器日志只有 Vite 连接与热更新 debug，无 error/warning。QA 数据和对象保留用于后续审计与摄取 E2E，不将待执行任务描述为摄取成功。

#### 构建、测试与失败证据

- 后端 `mvn clean package -DskipTests`：7 个模块 BUILD SUCCESS，总耗时 8.820 秒；隔离 8092 启动成功。首次受限启动因 Nacos 客户端需写用户目录日志被沙箱拒绝，改在已授权本机环境运行后成功，不属于应用启动失败。
- Java 17 最终扩大测试命令：`mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest='*Rag*Test,ChatServiceAuthorizationTest,WorkflowExecutorConfigTest' -Dsurefire.failIfNoSpecifiedTests=false`；131/131 通过，0 failure、0 error、0 skipped，六模块 BUILD SUCCESS，总耗时 3.880 秒。
- 同一测试此前有两批环境型失败且均保留：默认 Maven 使用 Java 25 时 131 项中 18 error，均为现有 Byte Buddy 版本不支持 class version 69；切换 Java 17 但留在受限沙箱时 67 error，均为 Mockito inline mock maker 无法自附加 agent。最终使用项目规定的 Temurin 17 并在允许 agent attach 的本机环境通过；两批错误都发生于 mock 初始化，没有业务 assertion failure，且不计入通过数。
- 前端最终 `npm run build`：TypeScript 检查通过，1916 modules transformed，1.00 秒；RAG 页面 CSS 20.63 kB（gzip 4.33 kB）、JS 32.11 kB（gzip 10.62 kB），入口 JS 165.53 kB（gzip 63.75 kB）。
- `git diff --check` 的全仓检查只命中不属于本阶段且明确不提交的运行日志尾随空格；对本阶段代码、测试、fixture 与计划文档的目标路径检查通过。未把日志、`data-alloy/`、`data/object-storage/`、设计草稿或 `skills/` 纳入本阶段提交。

#### 尚未宣称完成的边界

- 本阶段验证了管理 UI、真实上传注册、重复内容幂等和 Worker 关闭条件下的无租约取消一致性；尚未把 PDF/DOCX/Markdown 真实摄取到 Qdrant、召回并生成回答。因此不能称为 RAG 全链 E2E。
- 公共语料评测、Dense/Sparse/Hybrid/Rerank 消融、并发压测与瓶颈归因仍属于后续阶段；本节中的页面耗时和单次服务响应不作为性能基准数据。

### 阶段 5 执行计划：公开基准、Java 评测框架与检索消融

1. 只从官方论文、官方仓库或数据集主页筛选公开 RAG/IR 评测集，优先选择可重现且许可清楚的中英文子集。固定来源 URL、commit/tag/revision、许可、原始压缩包与规范化文件 SHA-256、文档/查询/qrels 数、字段映射和排除规则；原始大语料不提交 Git，只提交下载清单、转换脚本、微型 smoke fixture 和报告。
2. 在 Java 项目内实现独立、可恢复的评测命令与数据适配器，不绕过生产检索核心。数据摄取使用专用 tenant/kb/profile/target 命名空间，任务和 Qdrant point 可幂等重跑并可清理；评测查询不得混入正式租户数据。
3. 固定四组消融：Dense-only、现有 hashing log-TF Sparse-only、Dense+Sparse+RRF、Dense+Sparse+RRF+Rerank。除被消融组件外，候选数、最终 top-k、数据快照和评分脚本保持一致；随机性固定 seed，冷/热阶段分离，失败查询和降级均计入结果而非丢弃。
4. 检索指标至少输出 Recall@1/5/10、MRR@10、nDCG@10、MAP@10、Success@10，并保存逐查询排名与 qrels 对照。只有数据集提供可验证 gold answer 且完成回答生成链时才报告回答指标；否则明确标记“未评测”，不使用检索相关性冒充答案正确率。
5. 建立评测 run manifest：代码 commit、JDK/模型 revision、服务端点的脱敏标识、collection、文档格式、chunk 参数、线程数、batch、top-k、warmup、重复次数、开始/结束时间、机器与中间件资源快照、错误/超时数和每个输出文件哈希。所有最终数值由程序产物生成，报告不手工杜撰。
6. 先用小型公开子集打通下载→转换→摄取→四组检索→评分闭环，核对一组可人工判断的 qrels；再扩大到服务器资源能承受的固定规模。若模型/公网/磁盘限制阻止全量，保留失败证据并报告实际完成规模，不外推成全量结论。
7. 阶段完成后把官方来源、数据版本、真实命令、接口、线程/批次、耗时、指标表、失败和统计限制追加到本文，并用中文本地提交；随后才进入并发性能和瓶颈归因阶段。

### 2026-07-19 阶段 5 阶段性结果（一）：Java 基准基础与 SciFact 全量准备

#### 官方来源与许可核验

- BEIR 官方仓库的 [Datasets available](https://github.com/beir-cellar/beir/wiki/Datasets-available) 明确给出 SciFact 下载地址、test 查询 300、约 5K corpus 和压缩包 MD5 `5f7d1de60b170fc8027bb7898e2efca1`；同时官方免责声明要求使用者自行核对原数据许可，不能只把“BEIR 可下载”当作授权结论。
- SciFact 原作者仓库 [LICENSE.md](https://github.com/allenai/scifact/blob/master/LICENSE.md) 明确区分：claims/evidence annotations 为 CC BY 4.0，corpus abstracts 来自 S2ORC、为 ODC-By 1.0，代码为 Apache 2.0。本项目 manifest 因此记录复合许可，不笼统写成单一 Apache 或 CC 许可。
- 带 gold answer 的后续生成评测优先候选为 [RAGBench 官方数据页](https://huggingface.co/datasets/galileo-ai/ragbench)，固定可核验 revision `d568091d7b5765d5eb05bb8cbdf116bbc5da0917`、CC BY 4.0；它提供给定上下文及相关性/利用率/完整性标注，但不是全库检索 qrels，因此只能作为生成与上下文使用评测补充。中文 CRUD-RAG 官方仓库未清楚声明新闻语料许可覆盖范围，暂不下载或再分发，不能因为仓库徽章为 Apache 2.0 就推定数据也同许可。
- 官方 zip 下载到 `ai-agent-scaffold-benchmark/target/datasets/`，不进入 Git。实际大小约 2.7 MiB；MD5 与官方完全一致；额外计算 SHA-256 为 `536e14446a0ba56ed1398ab1055f39fe852686ecad24a6306c80c490fa8e0165`。

#### 新增 Java benchmark 模块

- 根 Maven reactor 新增 `ai-agent-scaffold-benchmark`，与在线 app 解耦；产物含普通 jar 和 Assembly 可执行 CLI，后续通过 HTTP 走生产上传/profile/binding/retrieval-debug 链，不复制 Embedding、Sparse、RRF 或 Rerank 实现。
- `BeirDatasetLoader` 严格读取 UTF-8 corpus/queries JSONL 与 qrels TSV，限制行长/记录数，拒绝重复 ID、悬空 qrels、负相关性和无正例查询。真实 SciFact 首轮发现 queries 文件含 train+test 共 1109 条，而 test qrels 只覆盖 300 条；加载器现以指定 qrels split 过滤查询。
- 支持固定 seed 的 positive-closed deterministic 子集：所选查询的全部正例必须保留，再以稳定哈希补足干扰文档；该策略在 manifest 明示，结果只能按实际子集规模报告。
- 生成 Markdown 使用 `BENCH_DOC_B64_<base64url>` 一级标题保存原始 doc ID，正文中形如 Markdown 标题的行会转义，防止不可信语料逃逸到另一 section；分片按 UTF-8 实际字节上限，默认 4 MiB。映射表、规范化 query/qrels 和每个输入/输出文件的 SHA-256 写入 manifest；输出目录必须为空，禁止静默覆盖。
- 评分器实现 Recall@1/5/10、MRR@10、graded nDCG@10、MAP@10、Precision@10、Success@1/5/10。MAP@10 明确以全部正例数为分母；缺失 run 查询按零分计，未知 query 和重复 ranked doc 直接拒绝。SciFact 没有 gold answer，score manifest 固定写 `answerMetrics=not_evaluated_no_gold_answers`。

#### 真实构建、CLI smoke 与失败留痕

- Java 17 模块最终 `mvn -pl ai-agent-scaffold-benchmark package -DskipTests=false`：6/6 tests 通过，0 failure/error/skipped，BUILD SUCCESS，总耗时 1.640 秒；生成 `ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar`。
- Java 17 全 reactor `mvn package -DskipTests`：新增 benchmark 在内 8/8 模块 BUILD SUCCESS，总耗时 8.486 秒；该命令只验证全部模块编译/打包，测试通过数仍以单独的 6/6 命令为准。
- 首次测试在代码编译后因受限沙箱不能写 `~/.m2` Surefire provider 而中止，未执行测试；转到允许 Maven 缓存的本机环境后通过。首次 fat-jar 使用 Shade 时，插件继承配置导致 Manifest transformer 参数解析失败；测试仍为 6/6，通过改用 Assembly 后 CLI 打包成功。两次失败均保留，不能计作通过。
- Git 内保留 3 文档/2 查询/2 qrels 的 `beir-mini` 合成 fixture。CLI prepare 成功；CLI score 的固定 run 得到 Dense：Recall@10=1、MRR@10=0.75、nDCG@10=0.8154648768、MAP@10=0.75；Hybrid fixture：四项均为 1。这些数值只验证评分数学与产物，不是任何模型或中间件效果。
- 真实 SciFact 第一次 prepare 输出 5183 documents、1109 queries、339 qrels，暴露 split 过滤缺陷，因此该目录不用于评测。修复后第二次输出 5183 documents、300 test queries、339 qrel pairs；生成 2 个 Markdown 分片，实际字节 4,192,977 和 3,764,696，均小于 4 MiB。
- 真实源文件 SHA-256：corpus `dec31c8182f3d744c7d2c09423756fd1d17cbef75808db13ba01cc0aab4d1ac6`、queries `8ff84a7c903f722981cd8d595c022660140c51867b27608a6d4910db86080313`、test qrels `0864bb985e0ca2367ba217977e72004d549054b2b06666ed9d4825ac7c21284c`；两个 Markdown 分片为 `d2db7a2543dd1725f4d88e4e233d36ec9e61c0eac937827810a01a7d4ae20399`、`52a3379bd87a62299aa8e782dbcb4b8c31ebbdfa61779f5f4b565f16bf71dbd9`。

#### 当前边界与下一切片

- 当前只完成公开数据准备和离线评分基础，没有把 SciFact 上传、摄取或查询，因此没有任何真实 Dense/Sparse/Hybrid/Rerank 指标可报告。
- 下一切片实现 Java 17 HTTP 黑盒 runner、四 profile/四 binding、上传任务轮询、引用 heading 解码、逐查询 checkpoint、降级/错误统计和延迟分位数；默认使用专用 benchmark 租户并保留现场，因为生产 API 尚无知识库/profile 的完整异步删除闭环。

### 阶段 5 第二切片执行计划：生产链黑盒 Runner 与语料代际一致性

1. 先验证生产上传上限、Markdown 本地解析上限和知识库 generation 语义。当前检索只读 KB 单一 active generation；若多个 benchmark 分片先后激活会隐藏早期分片，runner 必须在执行前拒绝不安全的多分片输入。SciFact 优先提高受控单分片上限，在现有上传/解析上限允许时生成一个约 7.6 MiB 文件；如果不允许，则先实现“同一批次共享 generation”的后端闭环，不能用不完整语料评测。
2. 在独立 benchmark 模块实现 Java 17 `HttpClient`：Bearer 只从环境变量读取，不进入参数回显、日志或 manifest；统一解析应用 `Response<T>`，限制响应体、超时和重试范围。提供知识库创建、multipart 上传、任务轮询、profile/binding 创建和 retrieval-debug。
3. 用唯一 runId 创建专用 KB、四个 profile 和四个 Workflow target binding。四组固定 Dense、Sparse、Hybrid+RRF、Hybrid+RRF+Rerank；除被消融组件外统一候选范围、finalTopK=10、neighborWindow=0、上下文预算和去重参数，Rerank 候选不超过服务 batch 上限 16。
4. 上传后轮询真实任务到 terminal；任何 ingest 失败、取消、超时或文档未 READY 立即停止查询，保留 task/revision/stage/errorCode。引用必须从 `BENCH_DOC_B64_` heading 无损解码，按原始文档首次出现去重；heading 缺失/非法使该查询失败，禁止猜测。
5. 固定 seed 交错四组查询顺序，warmup 与 measured 分离；每条结果立即追加 JSONL checkpoint，记录 retrievalId、wall/service/各阶段耗时、候选数、降级原因、原始 doc 排名和错误。恢复时以 runId+variant+query+iteration 去重。
6. 聚合质量指标、失败/降级/空结果率以及 wall/service/阶段 p50/p95/p99/mean/max；输出绝对值与组间差值，但在没有 bootstrap 或重复实验时不声称统计显著。先跑 beir-mini 真实 E2E，再评估是否启动 SciFact 全量。
7. 增加纯协议测试和本地 fake HTTP Server 黑盒测试，覆盖认证脱敏、multipart、任务失败、profile 消融参数、引用解码、断点恢复和响应上限。执行后把接口、线程、批次、文件大小、命令、测试与真实结果追加到本文，重大闭环中文提交。

### 2026-07-19 阶段 5 阶段性结果（二）：生产 HTTP 黑盒 Runner

#### 执行与留痕闭环

- 新增 `run` 子命令，只通过指定环境变量读取 Bearer，命令行、返回对象和 manifest 都不保存凭证值。基础 URL 必须是 HTTP(S)，响应体有可配置硬上限，应用 `Response<T>` 的非 `0000` 业务码与 HTTP/协议错误分开记录。
- Runner 通过生产 `/api/v1/rag` 接口依次建库、multipart 上传、轮询任务、查询文档 READY、创建四个 profile 和四个 Workflow binding，然后调用 retrieval-debug。摄取 failed/dead/cancelled/超时或文档未 READY 都会在检索前终止。
- 四组参数固定为 Dense-only、Sparse-only、Hybrid+RRF、Hybrid+RRF+Rerank；dense/sparse topK=100、fusion/rerank topK=16、final topK=10、neighborWindow=0、maxContextTokens=32768、query rewrite=false、deduplicate=true。warmup 单独落 `warmup.jsonl`，measured 按 query 轮换起始 variant 交错执行，查询和上传线程均为 1。
- 每条 measured 结果立即 append 到 `run.jsonl`，记录 runId、query SHA-256、retrievalId、原始文档排名、wall 耗时、六个服务阶段耗时、四类候选数、降级原因和稳定错误码。引用仅认 `BENCH_DOC_B64_` heading，同文档多 chunk 按首次出现去重，无法解码的查询记为失败和空排名，不猜测 ID。
- `metrics.json` 同时保存检索质量与运行统计：error/degraded/empty 数与比率，wall、阶段耗时及候选量的 mean/P50/P95/P99/max；分位数固定为 nearest-rank。manifest 保存 code revision、JDK、OS/架构、逻辑 CPU数、数据 revision/hash/bytes、seed、线程和四组参数，凭证只记环境变量名。
- 黑盒评测前置校验必须只有一个 Markdown 分片。根据现有“每次文档激活推进 KB generation”语义，顺序上传两个 SciFact 分片会使前一分片退出活动代际。因此已在忽略的 target 产出一个约 7.6 MiB 的 SciFact 单分片，低于现有 50 MiB 上传和 64 MiB 本地 Markdown 解析边界；没有用两分片做不完整评测。

#### 真实测试结果与失败记录

- Java 17 最终测试：`mvn -pl ai-agent-scaffold-benchmark test -DskipTests=false`，10/10 通过，0 failure/error/skipped，BUILD SUCCESS，总耗时 1.891 秒。包含本地 fake HTTP Server 全链路测试：3 文档/2 查询、4 消融组、8 条 measured 请求，验证上传到评分产物落盘、Bearer 脱敏、响应上限、引用去重和统计算法。
- Java 17 打包：`mvn -pl ai-agent-scaffold-benchmark package -DskipTests=false`，当时 10/10 通过，Assembly 可执行 jar 生成成功，BUILD SUCCESS，总耗时 1.616 秒。`java -jar ... --help` 已验证 prepare/score/run 三个入口可达。
- 统计测试首轮将两条有效 denseMs=20/30 的 mean 误写为 20，实际程序正确输出 25，导致当时 7 项中 1 failure。核对原始记录后只修正测试预期，业务统计代码未修改；最终结果为上述 10/10，失败轮不计入通过数。
- 全仓 `git diff --check` 仍只命中用户既有运行日志的尾随空格；本切片的 benchmark 源码、测试和本文不包含这些日志，亦不提交它们。

#### 未宣称完成的边界

- 本切片完成的是可执行与可审计的黑盒评测工具，fake Server 只证明协议和产物闭环，不是模型效果或服务器性能结果。尚未执行真实 beir-mini/SciFact 摄取与四组查询，因此本节没有报告任何真实质量或延迟数字。
- 现在每条查询已经 append checkpoint，中断时不丢已有原始记录；但由于生产 API 尚无完整 KB/profile 清理及评测资源恢复契约，当前命令要求空输出目录，还不支持跨进程 resume。下一切片先做真实 mini E2E，再根据实际摄取耗时决定是否在 SciFact 全量前补资源恢复/清理 API；不把本进程内落盘误称为完整断点续跑。

### 阶段 5 第三切片执行计划：真实 mini E2E 与 SciFact 准入判定

1. 按 `codex.md` 只读核对本机应用所需的 MySQL/Redis/Kafka/MinIO 与 RAG 服务端点，以及 RAG Worker 开关。密码和 API Key 只进入当前进程环境，不输出、不写评测 manifest、不提交 Git。
2. 不上传 Java/Vue 项目。使用本机隔离端口启动当前 commit 的 Java 应用，仅由本机应用通过网络调用 RAG 服务器上的 Qdrant、Embedding、Reranker 和 Docling 环境。原 8091 如果存活则不替换、不停止。
3. 先对网络端点、本机数据库迁移状态和应用健康做只读检查；如需迁移，只执行已验证的增量 RAG SQL，执行前备份/记录 schema 状态，不删除业务数据。
4. 使用 Git 内 `beir-mini` 生成单 Markdown，通过真实认证获取专用 benchmark tenant 的短期 token，执行真实建库→上传→Worker 摄取→文档 READY→四策略→四组检索→评分。记录接口基址、文档格式/字节/hash、查询数、warmup、线程、批次、开始/结束、错误和原始产物路径；账号密码/token 不留痕。
5. mini 准入条件：摄取 terminal=completed、文档 READY，四组各 2 条 measured 记录，无未解码 heading，指标由程序产物读取。任一条件不满足则停在 mini 查明原因，不启动 SciFact。
6. mini 通过后，根据实际摄取吞吐、服务器当时 CPU/内存/磁盘和模型错误率判定 SciFact 全量是否安全。若执行，固定 1 个 Worker、1 个上传线程、1 个查询线程和预备的 5183 文档/300 查询单分片；若不安全，保留真实证据并降到固定可复现子集，不外推为全量结果。
7. 本切片完成后追加真实操作、失败、产物 hash、质量/延迟数字和资源快照；对可归因性严格限定，没有重复和显著性检验时只报告观测差异。形成重大闭环后中文本地提交。

### 阶段 5 第三切片缺陷修复计划：Worker 与 Kafka 唤醒解耦

1. 保留 `ai.rag.worker.enabled` 作为摄取 Worker、MySQL 到期任务扫描和单线程执行器总开关；新增 `ai.rag.kafka.listener-enabled`，只控制 Kafka 事件唤醒。默认 false，未配置 Kafka 时 Worker 仍能依靠 MySQL 扫描恢复摄取。
2. Kafka Listener 只在 Worker bean 存在且 listener 显式开启时启动；不改变事件 payload、幂等键和 MySQL 回查语义。生产需要低延迟唤醒时必须同时显式开启两个开关。
3. 增加配置绑定/反射回归测试，证明 Listener 不再与 Worker 开关绑死；运行现有 RAG 扩大测试和六模块 compile。修复通过后才启动隔离本机应用。

### 2026-07-19 Worker/Kafka 解耦结果

- `RagProperties.Kafka` 新增默认 false 的 `listenerEnabled`，`application.yml` 暴露 `AI_RAG_KAFKA_LISTENER_ENABLED`；`RagIngestDispatcher` bean 和 MySQL `@Scheduled` 扫描仍由 `AI_RAG_WORKER_ENABLED` 控制，仅 `@KafkaListener.autoStartup` 改由新开关控制。Worker 与 Kafka 的 payload、任务回查和单线程语义没有改变。
- `RagPropertiesTest` 新增默认关闭和 Spring Boot 类型绑定断言。最终本机 Temurin 17 命令 `mvn -pl ai-agent-scaffold-app -am test -DskipTests=false -Dtest='*Rag*Test,ChatServiceAuthorizationTest,WorkflowExecutorConfigTest' -Dsurefire.failIfNoSpecifiedTests=false`：131/131 通过，0 failure/error/skipped，六模块 BUILD SUCCESS，总耗时 3.184 秒。
- 同一组测试先在受限沙箱中运行：124 项中 66 error、0 assertion failure，错误均为 Mockito inline mock maker 不能自附加 Java agent；不计入通过数。切换到允许 agent attach 的同一本机 Java 17 环境后得到上述最终结果。

### 阶段 5 第三切片缺陷修复计划：定时扫描与 Outbox 条件解耦

1. 先保留真实运行现场：隔离应用中 `RagIngestWorker` 和 `RagIngestDispatcher` 均已实例化，但任务长期停留 queued；全仓只有受 `ai.rag.outbox.enabled=true` 限制的 `RagOutboxPublisher` 标注 `@EnableScheduling`。先以此作为待验证根因，不把推断写成已证实结论。
2. 将 Spring 定时任务基础设施放入不受 RAG Outbox 开关影响的中央配置，移除 `RagOutboxPublisher` 上的 `@EnableScheduling`。Outbox 发布和 RAG Worker 扫描仍各自由原有业务开关控制，不改变调度周期、租约或单线程语义。
3. 增加轻量 Spring Context 回归测试，证明 Outbox 不存在/关闭时 `ScheduledAnnotationBeanPostProcessor` 仍被注册，同时确认扫描方法仍保留 `@Scheduled`。
4. 用 Java 17 运行 RAG 扩大测试与打包；重启 8092 隔离应用后，用数据库任务状态和 Worker 日志验证扫描真正发生。验证失败则记录新证据并继续定位，不启动 SciFact。

### 2026-07-19 阶段 5 第三切片阶段性结果：调度恢复与真实 mini 失败准入

#### 本机隔离运行与凭据边界

- 新增的 `start-local-rag-benchmark-app.sh` 只在本机 8092 启动当前 jar，原 8091 未停止或替换，Java/Vue 项目未上传 RAG 服务器。启动参数强制关闭 Context Kafka、RAG Kafka Listener 和 Outbox，启用单 Worker/MySQL 扫描，使用 `/tmp/ai-agent-rag-benchmark/object-storage` 隔离本地对象存储。
- 首次不使用 Nacos 的启动分别因 MySQL 无密码和密码错误失败；`codex.md` 中当前 MySQL 密码对公网 root 实测仍是 1045。随后仅恢复 Nacos Config 读取现有数据源，同时用命令行参数覆盖评测开关；数据库连接成功。
- 第一次恢复 Nacos 时曾意外启动 Context Kafka consumer 并加入现有 group，发现后立即停止。后续启动脚本用最高优先级参数明确关闭 Context 和 Kafka，不再连入生产消费组。
- MinIO 路径未通过：`codex.md` 旧 Access Key 返回“Access Key ID 不存在”，Nacos 现有 MinIO 密钥为空。因此 mini 只验证隔离本地存储，不宣称 MinIO 真实链路通过。
- 新增 `run-local-rag-mini.sh` 为每次 run 生成独立租户和唯一 email/phone，短期 Bearer 只在进程环境中，注册/登录临时文件退出时删除。早期传空 email 曾触发 `user_account.uk_email` 唯一约束，不计作 RAG 结果。

#### 调度缺陷实证与修复

- 修复前，Worker 和 Dispatcher bean 都存在，但任务始终 queued；全仓 `@EnableScheduling` 只在受 `ai.rag.outbox.enabled=true` 限制的 `RagOutboxPublisher` 上。评测为了不接入 Kafka 关闭 Outbox 时，Spring 调度基础设施也被一并关闭，这是任务无法执行的根因。
- 新增无条件 `SchedulingConfig` 集中启用调度，移除 Outbox 类上的 `@EnableScheduling`。业务方法仍由各自条件 bean 和原 `@Scheduled` 参数控制，未改租约、频率或单线程执行语义。
- Java 17 扩大回归最终 133/133 通过，0 failure/error/skipped，六模块 BUILD SUCCESS，总耗时 5.857 秒。新增两项测试分别证明无 Outbox bean 时 `ScheduledAnnotationBeanPostProcessor` 存在，以及 Worker 数据库扫描仍保留 2000 ms 默认 fixed delay。
- `mvn -pl ai-agent-scaffold-app -am package -DskipTests` 在上述测试之后完成六模块打包，BUILD SUCCESS，总耗时 3.827 秒；该打包命令不重复计入测试通过数。
- 重启 8092 后日志出现 `scheduling-1` 并由它初始化 MySQL。全新 mini 任务随后被领取，状态从 queued 推进到 indexing 并最终 dead，证明 MySQL 到期扫描与 Worker 执行已真实恢复，不只是 bean 存在。

#### 真实 mini 准入结果

- 真实 runId 为 `mini-real-20260718T194213Z`，输入为 1 个 Markdown、3 文档、2 查询、2 qrels，warmup=0，上传/查询线程都是 1，输出位于 `/tmp/rag-benchmark-mini-real-20260718T194213Z`。
- 任务真实终止为 `dead/indexing/RAG_EMBEDDING_UNAVAILABLE`，因此未创建四组可评分 run，没有任何 Dense/Sparse/Hybrid/Rerank 质量或延迟指标。`upload.json` SHA-256 为 `62d7e8d021decb161c36864b08c183359a92add24f631ef9d68b516ce5e419eb`，失败 manifest SHA-256 为 `a5b4efccd73fce3b8dca7de7f934f67865d46a29be599b5d00c79e9496b7146c`。
- 当时的 Embedding probe 返回 HTTP 401、响应 179 bytes。后续已证实这不是 `codex.md` Key 失效：脚本错读了表格第 4 列的用户名“无”，真正 API Key 在第 5 列。首次 probe 还因 zsh 的 `status` 为只读变量而在发请求前退出；两次失败都不计作模型服务故障。
- 当时 SSH 返回 `Permission denied`也不是已记录密码失效：取值 awk 先匹配到了正文而不是凭据表格行，实际传入空密码。用户重新确认原密码后使用精确表格匹配，SSH 连接成功。上述两项错误判断已在后续结果中纠正。

### 阶段 6 第一切片执行计划：可复现并发压测与瓶颈证据协议

1. 不绕过生产检索链。扩展 Java benchmark CLI，让完成的 `run` 产出 variant→targetId 映射；新增 `load` 子命令复用这些 target 调用 `/v1/rag/retrieval-debug`，不在压测工具内复制 Dense、Sparse、RRF 或 Rerank 实现。
2. 压测参数全部留痕：固定 seed、variant、查询集 hash、并发级别、每 variant warmup 和 measured 请求数、请求/连接超时、开始结束时间、JDK/OS/CPU 以及代码 commit。默认对每个 variant 执行 100 次 measured，至少包含单并发与 10 并发；资源紧张时可通过显式参数降低，报告必须如实显示。
3. 采用有界固定线程池和同步起跑门，将固定数量请求按稳定序列分发；每条原始记录包含 concurrency、worker/sequence、variant/query hash、wall 延迟、服务六阶段耗时、候选量、错误、降级和空结果。单一 writer 按 sequence 落 JSONL，避免并发追加交叉污染。
4. 每个 concurrency/variant 输出 request count、throughput、error/degraded/empty rate、wall 和各服务阶段 mean/p50/p95/p99/max。额外计算 `clientAndQueueMs=max(0, wall-total)` 的同口径分布，只将最大阶段占比标记为“观测到的主导耗时”，不在无 CPU/内存/网络资源快照时声称已证明根因。
5. 明确闭环边界：压测客户端机器快照与 RAG 服务器资源快照分开标识；后者必须由同时段 Prometheus/Docker 指标提供。当 SSH/API Key 仍无效时，只运行 fake HTTP 协议与压测工具自测，不把 fake 数字写成业务性能基线。
6. 回归测试覆盖参数边界、同步并发峰值、精确请求数、错误计入分母、凭据脱敏、原始 JSONL 可重读和报告原子落盘。执行 Java 17 测试/打包后，将真实命令、通过数、耗时、失败与未验证项追加到本文档，重大闭环用中文本地提交。

### 阶段 5 第三切片补充缺陷计划：评测脚本凭据列解析修正

1. 以服务器密钥文件哈希、`codex.md` 表格正确列哈希和服务器本机 HTTP 返回为证据，区分“凭据失效”与“脚本取错列”。已观测到正确的 API Key 在第 5 列，脚本第 4 列实际读到用户名“无”。
2. 仅修改本机启动脚本的表格列号，不轮换、不打印、不提交新密钥；用 `bash -n` 和脱敏 HTTP probe 验证。
3. 重启 8092 隔离应用后重跑全新 mini，原失败 run 保留为证据。只有新任务 completed、文档 ready 且四组原始记录完整时才通过准入。

### 2026-07-19 真实 mini 准入通过与并发压测工具阶段结果

#### 凭据列解析纠正

- RAG 服务器密码未变；精确读取 `| RAG 专用服务器 |` 表格行后 SSH 成功。六个中间件容器 Qdrant、Embedding、Reranker、Docling、Gateway、Prometheus 均为 healthy，Node Exporter 为 Up。
- `codex.md` 第 5 列的 Embedding/Reranker/Docling Key SHA-256 分别与服务器密钥文件完全一致；未输出密钥原文。服务器本机和修正后的公网 Embedding probe 都返回 HTTP 200，1 个向量、768 维、响应 9455 bytes。
- `start-local-rag-benchmark-app.sh` 已将三项 API Key 取值从第 4 列修正为第 5 列，`bash -n` 通过。没有轮换密钥，`codex.md` 中的原凭据无需更改。

#### 真实 mini 质量与延迟

- 新 runId `mini-real-20260718T195514Z`，代码基线 `89acc4159be975a082f165814454e623a096f18c`，使用 262 bytes 单 Markdown，共3文档、2查询、2 qrels，1 Worker、1 上传线程、1 查询线程，warmup=0。从 runner manifest 开始到结束约 37.844 秒。
- 摄取任务终态为 completed/completed，3/3 chunks，revision=8，文档为 ready。四组各2条 measured，共8条，0 error、0 degraded、0 empty，所有 benchmark heading 解码成功。
- 在这个只有2查询的功能性 fixture 上，Dense、Sparse、Hybrid+RRF、Hybrid+RRF+Rerank 的 Recall@1/5/10、MRR@10、nDCG@10、MAP@10 和 Success@1/5/10 全部为 1，Precision@10 均为 0.1。这只证明真实链路与评分闭环，数据量不足以证明消融方法的质量差异或统计显著性。
- wall/service-total/client-and-queue 观测均值分别为：Dense 1732/993.5/738.5 ms，Sparse 1644.5/769.5/875 ms，Hybrid+RRF 1995/1154.5/840.5 ms，Hybrid+RRF+Rerank 2403.5/1505.5/898 ms。Rerank 组服务内 rerank mean=360.5 ms，比无 rerank 组增加了可观测阶段；但每组只有2个样本，不作性能 SLA 或根因结论。
- 主要产物 SHA-256：`metrics.json`=`8a87b12a9512d884855ece499e8199ea5f2fd2d5d723fd40585e1e668b8b2760`，`run.jsonl`=`4fb4933fdbb88a8c15bafc923cf7fedc711a35c602ce5a1a3819538dceda016f`，`run-manifest.json`=`45e720cf55e6619472d3f173b2af803c93e6e97d7e34712071cbfa0adc4ffe71`。原始目录为 `/tmp/rag-benchmark-mini-real-20260718T195514Z`。
- `2026-07-18T20:02:28Z` 的 Docker 快照是运行结束约 6.5 分钟后的事后状态，不是负载期间峰值。当时 Embedding/Reranker 各约 1.86 GiB/3 GiB（约62%），Docling 1.367 GiB/4 GiB，Qdrant 53.82 MiB/6 GiB；该数据只能证明静态容量，不用于宣称压测峰值。

#### 并发压测工具实现与测试

- 完成 `load` CLI 与 `RagLoadBenchmarkRunner`：复用生产 run 新增的 `targets.json`，按固定 seed 执行多并发级别、每 variant 固定 warmup/measured 数量，使用同步起跑门和有界固定线程池。所有 measured 记录按 concurrency/sequence 可追溯，由单 writer 生成 JSONL。
- 报告按 concurrency/variant 输出 throughput、error/degraded/empty rate、wall 及各阶段 nearest-rank mean/p50/p95/p99/max，并单独计算 `clientAndQueueMs=max(0,wall-total)`。“observedDominantLatencyComponent”排除总计 `totalMs`，只是观测分解；报告明示 closed-loop coordinated-omission 边界和“未由客户端采集服务器资源”。
- Java 17 首次编译时现有 10/10 测试通过，证明主代码可编译。新增并发和统计测试后，最终 `mvn -pl ai-agent-scaffold-benchmark package -DskipTests=false`：12/12 通过，0 failure/error/skipped，BUILD SUCCESS，总耗时 2.430 秒，Assembly 可执行 jar 生成。
- fake HTTP 测试实际执行 40 个请求：8 warmup+32 measured，并发级别 1/3，观测峰值至少2且不超过3，验证了边界而非业务性能。fake 耗时不记作 RAG 性能基线。

### 阶段 6 真实联跑补充计划：一次性租户认证生命周期

1. 保留“每个评测 run 使用独立租户、凭据不落长期文件”的安全边界。不为了复用 target 而将明文密码或 Bearer 写入 manifest。
2. 扩展本机 mini 编排脚本：当显式启用 load 时，在同一 shell 进程、同一短期 token 生命周期内顺序执行质量 `run` 和读取其 `targets.json` 的 `load`，两者完成后再由原 trap 清理认证临时文件和环境变量。
3. load 开关默认关闭，并发级别、warmup 和 measured 数必须从显式环境变量传入并由 CLI manifest 留痕。真实 pilot 使用 1/2/4/10 并发、每 variant 2 warmup+20 measured，不将小样本 p99 声称为稳定 SLA。
4. 该缺口是在 `mini-real-20260718T200511Z` 完成并生成 `targets.json` 后发现的；因 token/密码已按原安全策略销毁，该 run 不用于后续 load。修复后创建全新租户重跑，不从数据库反查或重置一次性账号。

### 2026-07-19 阶段 6 真实并发 pilot 结果

#### 执行参数与完整性

- 质量 runId `mini-perf-b3c1698` 先完成摄取、文档 READY 和四组检索，随后在同一一次性租户/token 内执行 load runId `mini-load-b3c1698`。代码 revision 为 `b3c1698b669be6c88411da53a3ef7d6eb13215ca`，凭据仅记录环境变量名。
- load 从 `2026-07-18T20:10:39.154158Z` 到 `2026-07-18T20:15:09.875588Z`，共 270.721 秒。固定 seed=20260719，2 条查询，4 个 variant，并发级别 1/2/4/10；每级别每 variant 2 warmup+20 measured。最终 32 warmup+320 measured 原始记录，0 error、0 degraded、0 empty。
- 工具使用 closed-loop 固定请求数；这不表示 open-loop 指定到达率下的过载能力。每 variant 只有 20 个 measured 样本，p99 等于最大值，只作 pilot 极值，不是稳定 SLA。

#### 吞吐、延迟和稳定性

| 并发 | measured | 阶段耗时 ms | 吞吐 req/s | 错误 | 降级 | 四组 wall p95 范围 ms |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 80 | 135069 | 0.592 | 0 | 0 | 1783–2803 |
| 2 | 80 | 63231 | 1.265 | 0 | 0 | 1557–2074 |
| 4 | 80 | 30105 | 2.657 | 0 | 0 | 1495–2045 |
| 10 | 80 | 14218 | 5.627 | 0 | 0 | 1863–2362 |

- 吞吐在1→2→4并发基本倍增，10并发仍达到5.627 req/s，且未出现错误、降级或空结果。这个范围内没有观测到明确吞吐拐点；但未继续提高并发，不能宣称系统容量上限。
- 1并发 wall mean/p95：Dense 1619.95/2040 ms，Sparse 1463.6/1783 ms，Hybrid+RRF 1714.45/2194 ms，Hybrid+RRF+Rerank 1953.4/2803 ms。10并发分别为 Dense 1590.5/1869 ms，Sparse 1441.2/1863 ms，Hybrid+RRF 1807.3/2121 ms，Hybrid+RRF+Rerank 2003/2362 ms。延迟没有随并发出现持续性恶化。
- 10并发时 Embedding p95：Dense 405 ms、Hybrid 373 ms、Hybrid+Rerank 395 ms；Rerank 组 rerank p95=327 ms。它们较低并发有增长，但仍未触发服务降级或超时。

#### 同时段服务器资源证据

- 服务器从质量 run 前到 load 后采样，名义间隔 5 秒；`docker stats --no-stream` 本身有采集耗时。严格按 load manifest 时间裁剪后每容器 38 个样本。CPU 超过100% 表示使用多个核，不是百分比解析错误。

| 容器 | CPU mean | CPU max | 内存上限占用 max |
|---|---:|---:|---:|
| Embedding | 30.45% | 155.93% | 62.12% |
| Reranker | 22.57% | 200.02% | 62.27% |
| Qdrant | 1.49% | 7.04% | 0.94% |
| Docling | 2.30% | 28.24% | 34.18% |
| Gateway | 0.44% | 6.02% | 11.62% |
| Prometheus | 1.47% | 19.96% | 14.34% |

- 在16核服务器上，Embedding 峰值约1.56核、Reranker 峰值约2核，Qdrant CPU/内存都很低；本 pilot 没有证据表明 RAG 服务器 CPU 或内存饱和。内存的主要固定成本是两个模型常驻，各约1.86 GiB，而不是随并发快速增长。

#### 当前瓶颈定位边界

- 四组在所有并发级别下，`wall-total` 均值约600–782 ms，且是当前报告中最大的非汇总分量。源码核对表明 `RagRetrievalService.totalMs` 从 domain retrieve 开始计时，但在同步 `recordAudit` 之前就已固定；而审计随后开启 MySQL 事务，执行主记录 insert、引用 batch insert 及事务提交。
- 因此现有 `clientAndQueueMs` 名称过度简化：它实际包含审计写入、controller/JSON、HTTP/本机网络和客户端排队，不能将全部 600–782 ms 归因于队列。下一切片必须将其改名为边界外差值，并在生产返回中单独暴露 audit 和完整 service 耗时，再决定是优化审计写路径、配置查询还是网络。
- 产物 SHA-256：`load.jsonl`=`206d146e12d4bdf4b07842cd0b69ae024d6911d0b354bacf309e04bd7d81e05e`，`load-report.json`=`5a5c31893e6209dfeb81911a6551082858e6a4c98760d0aaf091eaaeee8165f0`，`load-manifest.json`=`148592533db1bc19da8e8c06bcf37798d36bd903666ec97af87ece7947915164`，`warmup.jsonl`=`1efac7d9da3dedc2352a062e0facc23db633900e78e9138de4c4ae767d3bdf41`。原始目录为 `/tmp/rag-benchmark-mini-load-b3c1698`，服务器采样下载副本为 `/tmp/rag-load-docker-stats-b3c1698.tsv`。

### 阶段 6 第二切片前置计划：RAG 服务器凭据再确认

1. 以用户最新提供的 RAG 服务器密码为准，核对 `codex.md` 的唯一凭据表记录；若内容一致则不制造无意义改动，也不在日志、测试产物或提交信息中重复输出密码。
2. 使用 `codex.md` 中的凭据进行一次只读、脱敏 SSH 连通检查，只读取主机名和 RAG 容器运行状态；不上传本地 Java/Vue 项目，不修改服务器配置。
3. 将实际连通结果、容器健康状态和失败项追加到本文档。该前置检查完成后，再进入检索配置、数据装载、组装、审计和完整服务耗时的分段计时实现。

#### 2026-07-19 前置检查执行结果

- `codex.md` 的 `RAG 专用服务器` 唯一凭据记录与用户最新提供值一致，因此未重复修改凭据，也未在命令输出和提交信息中输出密码。
- 使用该记录完成只读 SSH 检查，远端主机名为 `ser570412309881`。检查期间只执行 `hostname` 和 `docker ps`，未上传本地 Java/Vue 项目，未修改服务器文件或配置。
- `rag-qdrant`、`rag-embedding`、`rag-reranker`、`rag-docling`、`rag-model-gateway`、`rag-prometheus` 均为 `Up 6 hours (healthy)`；`rag-node-exporter` 为 `Up 6 hours`。本次凭据与 RAG 环境连通性前置检查通过，无失败项。

### 阶段 6 第二切片执行计划：检索全链路分段计时与审计边界校正

1. 保留现有 `totalMs` 的“检索管线、不含审计”语义以兼容既有调用；新增配置解析、数据库数据装载、纯组装、同步审计和完整服务耗时，贯穿领域结果、调试 API 与 benchmark 原始记录。
2. 配置耗时覆盖知识库绑定、知识库和检索画像读取；数据装载耗时覆盖 chunk、文档和相邻 chunk 读取；纯组装耗时用组装总耗时扣除其中的数据装载，所有差值向零截断，避免重叠统计。
3. 同步审计继续保持当前可靠性和事务语义，只测量耗时，不在没有数据前改为异步。完整服务耗时统计到审计尝试结束；审计失败仍沿用现有降级记录策略，同时返回实际耗时边界。
4. 将已有审计表 `assemble_ms` 字段真正写入，并把可在审计前确定的配置/装载阶段写入扩展 JSON；不为了本次计时新增数据库迁移。审计自身耗时只返回给调用方，避免为记录自己的写入耗时再追加一次数据库更新。
5. benchmark 对新旧响应兼容解析；负载报告将含混的 `clientAndQueueMs` 改为 `outsideReportedServiceMs=max(0, wall-serviceMs)`，并在旧响应没有 `serviceMs` 时明确回退到 `totalMs`。主导阶段分析排除 `totalMs`、`serviceMs` 等汇总项。
6. 补齐构造器、序列化、审计映射、HTTP 解析和统计测试，执行 Java 17 定向测试与 benchmark 全量测试。随后用相同参数做一轮真实小样本复测，比较审计、边界外和各内部阶段；所有命令、结果、失败和未验证项追加到本文档后再中文提交。

### 阶段 6 第二切片补充缺陷计划：重复检索审计引用主键冲突

1. 旧 8092 应用退出日志显示，同一评测租户的首批检索后，大量 `RagRetrievalService` 审计写入因 `DuplicateKeyException` 失败。先核对引用表主键/唯一键和引用 ID 生成范围，确认是否由跨检索复用确定性 `citationId` 导致，不用猜测代替证据。
2. 若引用 ID 当前只绑定 chunk 与 rank，则将其改为检索实例范围内稳定：纳入 `retrievalId`，保证一次响应内可追溯、同一检索重放确定，同时不同检索不冲突；不扩大数据库结构变更。
3. 新增同一查询连续检索两次的测试，断言引用 ID 不同且两次审计都被调用；保留引用与 retrieval 的关联。完成定向测试后，真实复测除延迟字段完整外，还必须检查应用日志无审计重复键错误。

#### 2026-07-19 阶段 6 第二切片执行结果

##### 全链路计时与统计语义

- `RagRetrievalResult.Metrics` 新增 `configurationMs`、`hydrationMs`、`assemblyMs`、`auditMs`、`serviceMs`，保留旧十参数构造器和 `totalMs` 的检索管线语义。`serviceMs` 计时到同步审计尝试结束，审计失败仍不覆盖成功检索结果。
- 配置计时包含 binding/knowledge-base/profile 解析；数据装载计时包含命中 chunk、父/相邻 chunk 和文档读取；组装计时扣除了组装期间的数据装载时间。调试 API 已返回全部字段。
- 审计表已有的 `assemble_ms` 现在实际写入；扩展阶段 JSON 增加配置与数据装载耗时。审计写入前无法知道审计自身耗时，故没有为记录自身耗时再增加一次数据库更新。
- benchmark HTTP 客户端兼容读取全部新字段。负载报告把含混的 `clientAndQueueMs` 改为 `outsideReportedServiceMs`，优先以 `wall-serviceMs` 计算，旧响应才回退 `totalMs`；主导阶段分析排除 `totalMs`、`serviceMs` 汇总项。

##### 重复检索审计缺陷修复

- 表结构证据：`rag_retrieval_citation` 有唯一键 `(tenant_id, citation_id)`，而旧 `citationId` 只由 tenant/version/chunk/rank 生成；相同 chunk 在后续检索中会复用 ID。旧 8092 日志中的连续 `DuplicateKeyException` 与该约束一致。
- 引用 ID 现纳入 `retrievalId`，因此同一检索实例内仍稳定可追溯，不同检索不再冲突；不需要数据库迁移。新增同一查询连续两次的回归测试，断言 retrieval ID、citation ID 均不同且两次审计都执行。
- 真实复测后只读查询数据库：本次租户有32条 `success` 检索记录；引用总数96、不同 retrieval ID 32、不同 citation ID 96。应用复测日志未出现新的审计重复键错误，修复闭环。

##### 测试过程与真实复测结果

- 首次 app 定向测试在沙盒内运行，33个测试中32个 Mockito 测试因 ByteBuddy 无法附着测试 JVM 而报环境错误；不是断言失败。沙盒外加 `-Djdk.attach.allowAttachSelf=true` 并 clean 编译后33/33通过。最终加入新断言和主键回归后，打包测试34/34通过，0 failure/error/skipped，BUILD SUCCESS，总耗时8.706秒。
- benchmark 首次在沙盒内 clean 测试时，4个本地 HTTP 测试因不允许绑定回环端口失败，其余8个通过；沙盒外最终13/13通过，0 failure/error/skipped，BUILD SUCCESS，总耗时2.840秒。
- 真实 runId `mini-timing-43b1d62` 完成质量链路；load runId `mini-timing-load-43b1d62` 使用1并发、每组1次预热+5次计量，共20条 measured。20条均有完整新指标，0 error、0 degraded、0 empty；阶段耗时32.244秒、吞吐0.620 req/s。样本太小，p95/p99不作为 SLA。

| 组别 | wall mean ms | total mean ms | service mean ms | audit mean ms | configuration mean ms | hydration mean ms | assembly mean ms | outside service mean ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Dense | 1508.2 | 892.0 | 1417.8 | 525.2 | 268.6 | 418.6 | 2.0 | 90.4 |
| Sparse | 1445.6 | 764.6 | 1330.2 | 564.6 | 262.0 | 433.6 | 2.0 | 115.4 |
| Hybrid+RRF | 1594.8 | 881.4 | 1475.8 | 593.4 | 244.8 | 416.0 | 2.4 | 119.0 |
| Hybrid+RRF+Rerank | 1898.2 | 1197.4 | 1768.8 | 570.4 | 284.0 | 497.2 | 2.0 | 129.4 |

- 原先600–782 ms 的 `wall-total` 已被证据拆开：同步审计是四组最大单项，均值525.2–593.4 ms；数据装载416.0–497.2 ms，配置读取244.8–284.0 ms；完整服务边界外仅90.4–129.4 ms。当前优化优先级应是减少远程 MySQL 审计/读取往返，其次才是模型或 HTTP 客户端；本切片没有未经测量就改成异步审计。
- 产物 SHA-256：`load.jsonl`=`489f58ed81b4592dd9b308e225c32296eec624546a3fab4db7f2b7739f3d57f2`，`load-report.json`=`c3cade89832a25fe1fe3690ef14ee7b5596592745f4a02b4e4509a650a7f8855`，`load-manifest.json`=`0d5c8cb9ccae3e71f4258621641228f4f96cd0d4f969fa9ac97a36ea34ec390e`。原始目录 `/tmp/rag-benchmark-mini-timing-load-43b1d62`。
- 数据库核验首次使用 `codex.md` 模糊表格匹配时误读到前一张中间件用途表，认证失败；按用户已提供的密码连接成功。随后一次查询误用不存在的 `ai_agent` 库名，改为应用配置的 `ai_agent_scaffold` 后得到上述计数。`codex.md` 实际密码记录正确，无需修改。

### 阶段 6 第三切片执行计划：批量装载引用文档

1. 基线证据显示每次返回3个不同文档引用时，数据装载包含1次命中 chunk 批量查询和3次逐文档查询，`hydrationMs` 均值416.0–497.2 ms。先减少确定存在的远程 MySQL 往返，不用本地缓存制造配置/索引状态陈旧窗口。
2. 在 `IRagRepository` 增加强租户 `listDocumentsByIds`，约束空输入、去重和最多500个 ID；MyBatis 使用单条 `tenant_id + document_id IN (...) + deleted=0` 查询，基础设施映射回领域实体。
3. 检索组装前一次性收集最终候选涉及的 document ID 并批量加载，组装时只从该快照取文档；缺失文档继续 fail closed，活动版本/generation 校验保持不变。由此将3个文档往返合并为1个，不改变检索排序、token 预算和引用内容。
4. 新增仓储批量边界和领域调用次数测试：多引用只调用一次批量文档接口，逐文档接口不再调用；跨租户/缺失范围仍不能静默通过。执行 Java 17 定向测试和 benchmark 回归。
5. 重启本机8092隔离应用，用与上一轮相同的1并发、每组1预热+5计量做真实对照。记录 hydration、total、service、audit、outside 的均值变化和原始产物哈希；小样本只用于方向性验证，不声称统计显著。

#### 2026-07-19 阶段 6 第三切片执行结果

- `IRagRepository`、`IRagDocumentDao` 和 MyBatis mapper 新增强租户批量文档读取：输入去重、空输入直接返回、单批最多500个 ID，SQL 始终包含 `tenant_id` 与 `deleted=0`。检索服务对超过500个不同文档的候选自动分批。
- 引用组装现在先从最终排序候选收集 document ID，再一次批量装载为请求内快照；后续组装不再逐条查询。缺失文档仍抛 `RAG_DOCUMENT_MISSING`，重复业务 ID 抛范围违规，活动版本与 generation 校验未改变。
- 测试验证两引用只调用一次 `listDocumentsByIds`、不调用 `findDocument`，并覆盖仓储去重和501条上限。打包定向测试45/45通过；补跑 MyBatis XML 装载后46/46通过，均为0 failure/error/skipped，最后一次 BUILD SUCCESS 总耗时2.701秒。
- 打包时曾覆盖正在运行的隔离 app jar，随后停止旧 JVM 时 Nacos/Logback 嵌套类加载出现 shutdown-only `NoClassDefFoundError`；HTTP 服务已先停止，未影响请求结果。之后使用新 jar 启动全新 JVM 完成对照；生产发布应通过版本化文件+原子软链/容器替换，不能原地覆盖运行中的 jar。

##### 同参数真实对照

- 优化 runId `mini-batchdoc-e6bd0c5`，load runId `mini-batchdoc-load-e6bd0c5`；参数与上一轮相同：1并发、每组1次预热+5次计量，共20条 measured。20条仍为0 error、0 degraded、0 empty；数据库为32条 success 审计，96条引用、32个不同 retrieval ID、96个不同 citation ID，日志无审计重复键错误。

| 组别 | wall 前→后 ms | hydration 前→后 ms | service 前→后 ms | audit 前→后 ms |
|---|---:|---:|---:|---:|
| Dense | 1508.2→1299.6（-13.8%） | 418.6→355.6（-15.1%） | 1417.8→1224.8 | 525.2→493.8 |
| Sparse | 1445.6→1214.0（-16.0%） | 433.6→368.0（-15.1%） | 1330.2→1134.4 | 564.6→489.4 |
| Hybrid+RRF | 1594.8→1361.8（-14.6%） | 416.0→353.4（-15.0%） | 1475.8→1269.8 | 593.4→494.6 |
| Hybrid+RRF+Rerank | 1898.2→1657.0（-12.7%） | 497.2→459.0（-7.7%） | 1768.8→1578.0 | 570.4→501.6 |

- 阶段总耗时从32244 ms降到27675 ms，闭环吞吐从0.6203升到0.7227 req/s（+16.5%）。新组配置均值212.2–226.2 ms、审计489.4–501.6 ms、边界外74.8–92.0 ms；同步审计仍是最大单项。
- 这是两轮各组只有5个样本、跨公网访问远程 MySQL 的先后对照。虽然四组 hydration 与 wall 都同向下降，但配置和审计也同步变快，存在网络时变影响；只能证明优化链路正确且方向有利，不能把全部12.7%–16.0% wall 改善归因于批量查询，也不能声称统计显著。
- 产物 SHA-256：`load.jsonl`=`fcac6fba7ca05bcb79bfe13dee7bbd1dcea73e90686896e7f690a667e52564c4`，`load-report.json`=`6f43bd15fa8df74b405cf6201b097ddf6349df46d4c54f82d0bf33bb70bcc6c1`，`load-manifest.json`=`157d526ce74192ba5e08304bb226523214e2d7affa57982f69856b834c7c4476`。原始目录 `/tmp/rag-benchmark-mini-batchdoc-load-e6bd0c5`。

### 阶段 7 第一切片执行计划：SciFact 真实检索质量消融

1. 重新从 BEIR 官方 SciFact 下载地址获取数据，不复用已被 clean 清理的临时文件。校验官方 MD5 `5f7d1de60b170fc8027bb7898e2efca1`、既有 SHA-256 `536e14446a0ba56ed1398ab1055f39fe852686ecad24a6306c80c490fa8e0165`，并再次确认复合许可与 test split 规模。
2. 使用现有 Java CLI 严格读取 corpus/queries/test qrels，准备完整5183文档、300 test 查询、339 qrel pairs。生成单个 Markdown 文件以符合当前知识库单 active generation 语义；记录源文件、规范化文件和 Markdown 的字节数、SHA-256、seed、映射策略与排除规则。
3. 启动本机8092隔离应用，固定1个摄取 Worker、1个上传线程、1个查询线程；本地 Java 项目不上传服务器。并行采集 RAG 服务器容器资源，摄取期间如出现 OOM、持续错误或任务终态失败则保留现场，不把不完整语料用于评分。
4. 通过真实生产 HTTP 链路创建专用租户、知识库、四组 profile/binding，完成 Dense-only、Sparse-only、Hybrid+RRF、Hybrid+RRF+Rerank。除消融组件外固定 topK、finalTopK=10、neighborWindow=0、上下文预算、去重与同一数据快照；warmup 与 measured 分离。
5. 对300查询逐组计量，共1200条原始记录。失败、降级、空结果和缺失查询全部保留并计入分母；逐查询保存 query hash、retrievalId、排序文档、候选数和全链路耗时，不保存 Bearer 或查询凭据。
6. 由程序产物计算 Recall@1/5/10、Precision@10、MRR@10、graded nDCG@10、MAP@10、Success@1/5/10，并输出四组绝对值和相对差异。SciFact 没有 gold answer，回答正确率、Faithfulness 与生成质量明确标记未评测，禁止用检索相关性替代。
7. 核对 `run.jsonl` 恰有1200个唯一 variant/query、qrels 覆盖300查询、无未知/重复文档；数据库审计计数、引用唯一性和应用日志作为辅助证据。将真实命令、耗时、资源峰值、指标表、原始产物哈希、失败及统计边界追加本文并中文提交。

### 阶段 7 第一切片补充缺陷计划：Embedding 429 背压恢复

1. 首次完整 SciFact 摄取在 indexing 阶段终止，数据库任务为 `failed/RAG_EMBEDDING_HTTP_ERROR`，模型网关访问日志为 HTTP 429，TEI 明确记录 `no permits available`。保留失败 run 目录和服务资源记录，不覆盖、不删除。
2. Embedding 适配器仅对 429、502、503、504 做次数有限、带上限的指数退避重试；400/401/403 等确定性错误不重试。每次响应体仍有界关闭，线程中断立即退出，耗尽后保留稳定错误码和 HTTP 状态，不做无限重试。
3. 将重试次数、初始退避和最大退避纳入强类型配置并设保守默认值；增加单元测试验证 429 后成功、耗尽、非瞬态不重试和中断语义。日志不输出 API Key、正文或服务响应体。
4. 本机隔离评测把 Embedding 批次从16降为4，仍保持单摄取 Worker、单查询线程，避免紧张服务器被一个大批次打满；该资源参数进入运行清单和最终结论，不把它与检索质量消融变量混淆。
5. 完成测试与重新打包后启动新 JVM，以新的 runId 和空输出目录重跑完整 SciFact；首次失败作为失败证据追加本文，成功重跑不得覆盖失败历史。

#### 补充吞吐调整

- batch=4 已经证明可稳定推进，但实测前99秒仅完成44/7548 chunks。只读检查部署参数确认 TEI 为 `max-batch-tokens=8192`、`max-client-batch-size=16`；batch=16 在接近512 tokens/chunk 时会越过总 token 许可，而 batch=8 留有前缀余量。
- 为避免数小时低效等待，先暂停 benchmark 轮询进程，再优雅停止本机8092旧 JVM，以 batch=8 启动同一代码和同一数据库任务；依靠既有 lease/checkpoint 从已完成向量位置恢复。新 JVM健康后恢复 benchmark 进程。全过程不改 qrels、查询、检索参数或语料，不构成质量消融变量。
- 恢复后核对 task attempt、fencing token、checkpoint 单调推进以及 Qdrant/数据库无重复激活；若 batch=8 仍出现耗尽重试的429，则回退 batch=4，不修改远端服务器算力配额。

#### 2026-07-19 SciFact 数据准备与 Embedding 背压修复阶段结果

- 从 BEIR 官方地址重新下载 `scifact.zip`，MD5=`5f7d1de60b170fc8027bb7898e2efca1`、SHA-256=`536e14446a0ba56ed1398ab1055f39fe852686ecad24a6306c80c490fa8e0165`，与官方清单和既有留档一致。源 corpus/queries/test qrels 的 SHA-256 分别为 `dec31c8182f3d744c7d2c09423756fd1d17cbef75808db13ba01cc0aab4d1ac6`、`8ff84a7c903f722981cd8d595c022660140c51867b27608a6d4910db86080313`、`0864bb985e0ca2367ba217977e72004d549054b2b06666ed9d4825ac7c21284c`。
- Java prepare 实际产出5183文档、300个 test 查询、339组 qrel；单 Markdown 为7,957,673 bytes，SHA-256=`0287493f09e9cb8d13d44bd46c01540229a7bad18d8c9da344f60429a89d6680`。`document-map.jsonl`、规范化 queries、qrels 的 SHA-256 分别为 `1718e1ed99f145f839156afccca3b13de7608a154232e5d829f20a36cb124c84`、`331f88f940774ac84e1fc6ef517720dd94d07deab77efbdc85f42fc405335ad0`、`5602d9f31c96d309a906692e1b722a9acfc4138c5d52e06d47bbb89a9c4ab7c3`。
- 首次 runId `scifact-quality-b00f9cc` 在 indexing 首批失败：任务 `failed`、attempt=1、checkpoint=0/7548、错误码 `RAG_EMBEDDING_HTTP_ERROR`。远端网关证据为 HTTP 429，TEI 日志为 `no permits available`；失败目录 `/tmp/rag-quality-scifact-20260719/run-scifact-quality-b00f9cc` 保留，未用于评分。
- `TeiEmbeddingAdapter` 现在仅对429/502/503/504执行可配置的有限指数退避，重试期间释放本地并发许可；默认最多3次、250ms起步、2s封顶，配置硬限制最多10次。401等确定性状态不重试，响应体仍受8MiB上限约束，线程中断会恢复 interrupt flag。
- 定向测试首次24项中23项通过；失败项把“调用前已中断”误期望成退避中断，实际稳定码为既有 `RAG_REMOTE_INTERRUPTED`。测试改为服务返回429后中断退避线程，最终24/24通过，0 failure/error/skipped，BUILD SUCCESS 2.719秒；随后本机 app 打包成功。
- 重跑 runId `scifact-quality-retry-b00f9cc` 先用 batch=4 推进到184/7548。只读检查部署确认 TEI `max-batch-tokens=8192`、`max-client-batch-size=16`，随后暂停 benchmark 轮询、优雅停止旧 JVM并以 batch=8启动。旧 lease 自然到期后，新 Worker 从 checkpoint 恢复：attempt 1→2、fencing token 1→2、进度184→232→320，错误码为空；再恢复 benchmark 轮询。该过程验证 checkpoint/fencing 闭环，未上传项目到服务器，未更改语料和检索变量。
- benchmark 脚本新增可外部配置的 warmup 查询数和摄取超时，默认行为保持0和900秒；本次明确为 warmup=10、ingest timeout=7200秒。成功重跑仍在进行，最终1200条结果和指标将在完成后继续追加。
- 成功重跑后续除服务器容器 `docker stats` 外，再按5秒间隔采集本机隔离 app JVM 与 benchmark JVM 的 CPU/RSS/VSZ；采集文件只记录 PID、进程角色与资源数值，不记录命令行、环境变量或凭据。最终按摄取、warmup、measured 时间边界汇总峰值与阶段差异。
- 当前 run 在背压修复尚未提交时启动，因此自动写入的 `codeRevision=b00f9cc...` 只代表启动时 HEAD，不足以唯一标识运行字节码。已在运行中且未重新打包时冻结证据：app JAR SHA-256=`ec0a0035aca50f5cf37f79f66f3b465a330e33dda265d8e87b58aba816771a3c`（323,829,376 bytes，构建时间2026-07-19 04:59:11+08:00），benchmark CLI JAR SHA-256=`3bb93739c42a6e8bb3cdea1856013a1bb178237eccb1b98c2e6e68ca84fdc470`。最终报告同时使用 JAR 哈希和提交 `a150d43`，并明确该提交比运行 JAR只多重试上限校验/亚毫秒保护，不伪称二者字节相同。后续脚本需补 runtime artifact hash/dirty 状态，避免只记录 HEAD。

#### 第二次受控吞吐调整

- batch=8 在长时间采样中约为1.2–2.2 chunks/s，Embedding 计算峰值约4.2核但大量时间等待本地应用到远程 MySQL 的 barrier/checkpoint 往返；按当前速率接近7200秒摄取超时边界。child 上限420估算 tokens，TEI总批次上限8192，batch=12 的理论上界约5040估算 tokens，保留足够 tokenizer 偏差空间。
- 前一次人为重启和一次真实心跳重领已使 attempt=3/maxAttempts=3。为让同一评测任务能安全完成，只对当前 taskId、`status=running`、`max_attempts=3` 做条件更新到6，并核对恰好影响1行；不改 checkpoint、fencing token、lease、generation 或文档状态。该运维干预和 SQL 影响行数必须留痕。
- 再次暂停 benchmark 轮询，优雅停止 batch=8 JVM，以 batch=12、600秒 lease、30秒 heartbeat 启动；等待旧 lease 到期并确认 attempt=4、fencing token递增、checkpoint单调后恢复轮询。如果实际出现连续429，有限重试耗尽后保留失败，不继续扩大批次或服务器资源。

#### 终态清理缺陷恢复计划（执行前）

1. 保持 benchmark JVM 暂停，冻结 batch=12 失败任务及 run 目录；先从源码、MySQL 与 Qdrant 三方核验失败后的真实副作用，不直接把终态任务改回 `retrying`。
2. 已由源码确认：重试耗尽后适配器抛出稳定码 `RAG_EMBEDDING_HTTP_ERROR`，但错误分类器未把该码识别为瞬态，Worker 因而走 `cleanupForTerminalFailure`，删除该版本全部 Qdrant 向量和数据库 chunks，再关闭任务、版本和文档。继续核对数据库 chunk 数、任务/版本/文档状态以及 Qdrant version filter 计数；若清理成立，禁止从 checkpoint 1504 伪续跑。
3. 修复错误语义：Embedding 适配器对429/502/503/504重试耗尽时抛出新的可重试稳定码，非瞬态4xx继续抛终态 HTTP 错误；错误分类器只把前者列为可重试。增加分类器和适配器回归测试，覆盖429耗尽为可重试、401为不可重试，避免永久配置错误无限占用 Worker。
4. 旧失败任务、错误码、checkpoint 和清理结果完整保留。通过生产 HTTP 链路新建独立 run/租户/知识库并从0重新摄取完整 SciFact，不复用已被清空的 version；使用已验证更稳定的 batch=8、600秒 lease、30秒 heartbeat，摄取超时按完整重跑重新计时。
5. 新任务必须先核对 attempt/fencing/checkpoint、数据库 chunk 与 Qdrant point 同步增长，才允许进入300查询×4消融评分。当前暂停的旧 benchmark 进程在证据收集后终止，不恢复成会误报成功的旧 run。
6. 将 batch=12 失败、错误分类根因、清理计数、代码测试、新 run 标识、运行参数和所有后续真实结果追加本文；修复闭环后进行中文本地提交，不纳入用户日志及无关文件。
7. 只读连接发现 `codex.md` 的 MySQL 凭据与用户本阶段明确提供值不一致；在不输出旧值和新值到日志/提交信息的前提下，仅修正本地凭据表，并用脱敏连接结果校验。`codex.md` 若按规范不受 Git 管理，则不强行纳入提交。

##### 终态清理缺陷核验与修复结果

- batch=12 任务在 attempt=5、fencing token=5、checkpoint=1504/7548 时以 `RAG_EMBEDDING_HTTP_ERROR` 关闭。源码证明 `cleanupForTerminalFailure` 会先删除 Qdrant version points 和数据库 chunks，再以事务关闭任务、版本和文档；MySQL 实查该 version 的 chunk count=0、版本 `failed/chunk_count=0`、文档 `failed/active_version_id=NULL/chunk_count=0`，Qdrant 以 tenant+version 精确过滤计数也为0。由此否决了直接恢复旧 checkpoint 的方案。
- 修复将瞬态状态429/502/503/504在有限重试耗尽后的稳定码改为 `RAG_EMBEDDING_TRANSIENT_HTTP_ERROR`，错误分类器只把该码视作可重试；401等非瞬态状态仍使用 `RAG_EMBEDDING_HTTP_ERROR` 并终态处理。这样既不会因模型网关瞬态背压清空整版数据，也不会让永久认证/请求错误无限重试。
- 首轮定向测试中协议适配器10/10通过，Worker 的4个 error 均为旧 Surefire fork JVM 未收到 Mockito attach 参数。使用 `-DargLine=-Djdk.attach.allowAttachSelf=true` clean 重跑后，`TeiModelAdapterProtocolTest` 10/10、`RagIngestWorkerTest` 6/6，共16/16通过，0 failure/error/skipped，BUILD SUCCESS 9.394秒；随后 app `package -DskipTests` BUILD SUCCESS 7.674秒。
- 旧 benchmark JVM在保持暂停的前提下收到中断并退出（exit 130），不会把已清空版本误用于评分；batch=12旧 app已优雅停止。新修复 JAR以 batch=8、最多5次HTTP重试、500ms到4s退避、600秒 lease、30秒 heartbeat 启动，PID=81885，8092健康且 MySQL连接池成功建连。
- `codex.md` 的凭据表已按用户本阶段明确提供的 MySQL 值修正；第一次脱敏校验仍误匹配到上方“中间件用途”表的同名行而失败，追加“用户名列包含应用配置说明”的唯一条件后 `SELECT 1` 成功。该歧义只影响临时校验命令，未把凭据打印到输出。
- 修复闭环已提交 `dba0df8 修复向量背压误判并保全摄取重试`。新 app JAR SHA-256=`2dc85c4467f632117183be64960a63a57f006934cda7510f93f74709dabda986`，323,829,932 bytes，构建时间2026-07-19 05:50:40+08:00；运行 manifest 的 `codeRevision=dba0df8b462f7d4243537e452fa23e98662c4eca` 与本次源代码修复提交一致。
- 全新 runId `scifact-quality-recover-dba0df8`、输出目录 `/tmp/rag-quality-scifact-20260719/run-scifact-quality-recover-dba0df8` 已从0启动，摄取超时14400秒、warmup=10，其余质量消融参数不变。新任务 `ragtask_02f68e795feb4f6fa9c196332032de6f`、version `ragver_d968b63fefc04e78baf2cbd4bf8e4b68` 在 attempt=1/fence=1 下从0推进到224/7548，错误码为空且心跳/600秒 lease 正常；稍后 Qdrant tenant+version 精确计数为232，时点差来自下一批向量已写入而 checkpoint CAS 尚未落库。
- 数据库该 version 的 `rag_chunk` 已为12749行，这是分块阶段一次性持久化的父子块总数，并非实时向量数；后续完整度核验以 checkpoint `vectorUpsertIndex`、Qdrant精确计数及最终 verification 为准。新的本机资源采样文件 `/tmp/rag-quality-scifact-20260719/local-process-stats-recover.tsv` 每5秒记录 app PID 81885 与 benchmark PID 85515 的 CPU/RSS/VSZ；旧服务器容器采样会按新 run 时间边界裁剪。

#### 质量查询 JWT 过期恢复计划（执行前）

1. 保留已完成索引和首次1200条查询产物。首次运行虽正常退出并写齐1200行，但前三组各281次、Rerank组280次 `RAG_BENCHMARK_HTTP_401`；只把它视为认证失效实验，不发布其中被失败分母污染的低质量指标。
2. benchmark HTTP 客户端改为可注入 token provider。请求遇到401时，只允许刷新一次并重放一次；并发场景按“被拒 token 是否仍是当前 token”合并刷新，防止负载测试登录风暴。其他HTTP状态不重放，Bearer、用户名、密码和登录响应都不进入异常、manifest或结果。
3. CLI从独立环境变量读取刷新用用户名/密码；脚本继续生成临时评测账号并导出凭据，退出时清理。增加401→重新登录→成功、刷新失败、非401不刷新及敏感值不泄漏测试。
4. 新增只读复评分入口：读取既有 `targets.json`、prepared queries/qrels 与同一已完成知识库绑定，执行 warmup+300×4 查询并重新生成独立 run/metrics，不重新上传或摄取7548块。入口必须校验4个固定 variant、目标ID唯一、prepared哈希和目标文件哈希并写入manifest。
5. 修复测试与打包通过后，以新runId复用 `scifact-quality-recover-dba0df8/targets.json` 完整执行1200查询；要求0个401、1200个唯一 variant/query，再据此报告 Recall/Precision/MRR/nDCG/MAP/Success 和延迟。若出现新的系统错误，继续按真实失败处理，不用首轮19个成功样本外推。
6. 首轮脚本按旧逻辑退出时已删除临时明文凭据，无法通过登录刷新既有 benchmark tenant。为避免重新摄取7548块，只对该明确的 benchmark tenant 管理员执行一次条件密码重置：先核对用户名/角色/租户和唯一影响行，使用随机临时密码的 BCrypt 哈希更新，完成复评分后该隔离租户仍仅用于 benchmark；不触碰正常租户、知识库归属或索引数据。凭据只存在受限临时目录/环境变量，绝不写入计划、日志、manifest或Git。

##### JWT 过期根因与恢复实现结果

- 首轮索引最终 completed，7548/7548，任务 attempt/fence=2/2、错误码为空；run 输出恰有1200行。但逐行归类证明 dense/sparse/hybrid_rrf 各281个、hybrid_rrf_rerank 280个 `RAG_BENCHMARK_HTTP_401`，仅前19/19/19/20条成功。manifest 从21:55到23:55约2小时，和JWT生命周期吻合，因此该轮 metrics 仅是失败分母验证，不作为质量结论。
- `RagBenchmarkHttpClient` 已支持 token provider：401时读取被拒token，执行一次并发安全刷新并重建 Authorization 后重放；第二次仍失败即按真实HTTP错误返回，非401不刷新。`RefreshingLoginTokenProvider` 对同一过期token串行刷新、对已被其他线程更新的token直接复用，登录响应有界读取，异常不包含凭据或响应正文。
- CLI可从独立用户名/密码环境变量启用刷新；脚本在受限临时认证目录之外只导出进程环境，并在退出trap中清除。新增 `evaluate` 模式可读取严格四变体唯一targets、同一prepared数据与独立空输出目录，只执行warmup、1200查询和评分；manifest记录targets SHA-256及复评分模式，不重新上传/摄取。
- 新增401→刷新→重放测试，验证首请求使用过期token、第二请求使用新token、只刷新一次且返回正常引用。benchmark全量14/14通过，0 failure/error/skipped，BUILD SUCCESS 3.027秒；`bash -n`通过，随后含依赖CLI打包再次14/14通过，BUILD SUCCESS 2.501秒。
- 既有benchmark tenant只存在一个active owner；其 `user_secret` 恰有一条active password记录和一条refresh_token记录，password hash长度60。后续密码条件更新将限定tenant/user/secret_type/status/deleted并核对影响1行。

#### 模型网关失活恢复计划（执行前）

1. 复评分启动后首批warmup暴露新的基础设施故障：Sparse可返回但耗时13秒，Dense/Hybrid约32秒并报 `RAG_EMBEDDING_UNAVAILABLE`；公网健康检查显示Embedding 8081在10秒内0字节超时，而Reranker端口可立即返回404、Qdrant healthz为200。立即中断该无效run并保留3条warmup，不进入measured。
2. 通过只读SSH检查 `rag-model-gateway`、`rag-embedding` 的容器状态、资源与最近日志，确认是gateway阻塞、TEI失活还是网络问题；不上传本地项目。若容器进程失去服务能力，则只重启最小相关容器，等待Docker health和真实授权Embedding请求恢复。
3. 同时重启已连续运行约11.5小时且Hikari出现多次通信断开的本机隔离app，以新JVM重建数据库连接池；参数保持batch=8/重试/lease配置不变。先用单次真实debug验证Dense、Sparse、Hybrid和Rerank链路，再开启新的空目录复评分。
4. 第三轮必须从warmup起0错误；若Rerank仍降级，先定位Reranker接口契约/超时，不能把 fallback 结果当“加rerank”组。只有四组实际组件均按配置执行，才允许完成质量消融。

##### 模型网关与本机应用恢复的阶段结果

- 只读服务器核验显示 `rag-model-gateway`、`rag-embedding`、`rag-reranker`、Qdrant 均持续运行，Embedding 经 gateway 的真实请求返回200，Qdrant `/healthz` 返回200；公网8081的 `/health` 超时是 gateway 没有该健康路由，不能据此判定 TEI 宕机，因此没有无依据地重启服务器容器。
- 第二次复评分无效 run `/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-63ae2a0` 只产生3条warmup：Sparse成功，Dense/Hybrid报 `RAG_EMBEDDING_UNAVAILABLE`，未产生 measured；该进程已中断并保留现场。旧本机 app 同时出现远程 MySQL stale connection/Hikari 通信失败，已优雅停止并用相同批次、重试、lease、heartbeat参数启动新 JVM，Tomcat 8092 与新 Hikari 连接均恢复。
- 服务器日志进一步证明 Reranker 对16候选请求稳定在约7–8秒返回429，而不是容器退出或Qdrant故障。为让 `finalTopK=10` 的全部指标仍有完整候选空间，同时不超过当前重排服务的令牌/许可容量，本轮把新 profile 的 `fusionTopK` 与 `rerankTopK` 从16收敛为10；Dense/Sparse各自召回100、最终Top‑10、语料、qrels及其余变量保持不变。
- 调整后的 benchmark 全量测试14/14通过，0 failure/error/skipped，BUILD SUCCESS 3.325秒，含依赖CLI JAR已重新生成。下一步只更新本次 SciFact 既有4个专用 profile（带 revision 乐观锁），逐组做真实debug；必须四组均 `error=null`、`degraded=false`，且Rerank实际候选数和耗时大于0，才启动新的1200条复评分。

###### Reranker Top-10 仍降级的追加诊断计划

1. 4个专用profile已通过正式PUT接口从16更新为10，数据库核验 `fusion_top_k=10/rerank_top_k=10/final_top_k=10`；逐组真实debug中Dense、Sparse、Hybrid均成功且未降级，Rerank仍在17.343秒后以 `rerank_fallback` 降级，候选数和耗时均为0。因此不启动正式复评分。
2. 只读检查 Reranker 容器启动参数、资源限制和本次请求附近日志，区分单请求token上限、客户端批次上限、并发permit和超时；同时检查Java适配器是否把10个候选一次性封装为服务契约允许的请求。禁止仅凭猜测把Top-10评测改成Top-8。
3. 若服务启动参数低于当前模型可承受的单请求Top-10，只调整RAG环境中间件的最小必要容量参数并验证内存峰值；若契约本身要求分批，则在Java适配器实现有界分批、合并原始分数再统一排序，并增加边界/错误/超时测试。
4. 修复后仍以同一查询逐组冒烟，Rerank必须 `degraded=false`、`rerankCandidateCount>0`、`rerankMs>0` 且返回10条引用；否则继续保留失败证据，不发布Rerank质量指标。

###### 旧业务服务器中间件迁移评估计划（执行前）

1. 用户允许在旧服务器资源/网络成为瓶颈时，把MySQL及经评估适合的中间件迁往新RAG服务器。先只读采集两台服务器的网络RTT、CPU/内存/Swap/磁盘、容器工作集与MySQL容量/连接/缓冲池/数据量，不因“服务器比较烂”的主观判断直接搬库。
2. 分开判断两条故障链：Reranker 429属于新RAG服务器模型服务容量；远程MySQL往返属于旧服务器/公网数据库路径。迁移MySQL不能修复Reranker，调整Reranker也不能消除数据库审计和引用装载延迟。
3. 迁移候选按依赖和数据一致性评估：优先考虑MySQL是否能与Qdrant共置；Kafka、MinIO、Nacos、XXL-JOB、Grafana/Loki默认不一起迁移，除非测量证明收益且新机内存、磁盘、故障域仍满足要求。尤其禁止把15GiB主机的容器资源上限简单相加当成可用容量。
4. 若MySQL迁移收益成立，先在新机部署版本一致、受限内存和非公网暴露的从库/恢复实例，完成全量备份校验、增量追平、表/行数与关键hash核验；再安排明确停写窗口、最终追平、应用/Nacos连接切换和端到端验证。旧库保留只读回滚窗口，不做不可逆删除。
5. 切换验收至少覆盖登录、会话历史、RAG配置/摄取/checkpoint、检索审计、定时任务和消息链路；记录切换前后P50/P95、错误率和资源峰值。任一关键核验失败立即回切，不用双主写入制造分叉。

###### 2026-07-19 Reranker 进一步诊断结果

- 前一次直连401来自临时诊断脚本误取凭据表第4列“用户名”，正确API Key在第5列；这不是服务器Key失效，Java启动脚本一直按第5列读取，因此不影响业务冒烟结论。错误探测已停止且没有输出密钥。
- 使用正确Key和无敏感合成文本直连：3候选返回200、1.713秒、3条分数；10个每条80字符的短候选返回429 `Model is overloaded`、0.788秒；10个每条1200字符的候选返回同一429、15.153秒。证明Reranker在线，但当前批处理/permit能力无法稳定承接Top-10，而非MySQL导致重排调用失败。
- 随后从4候选开始的临界点探测首请求在60秒内0字节并超时，说明连续过载后服务未及时恢复，不能用单一“最多N条”解释。当前更可能是CPU推理积压/permit未释放或进程失活；正式1200条复评分继续禁止启动。
- `codex.md` 记录的RAG SSH密码当前被服务器拒绝，无法进入容器核对日志或执行最小重启；该凭据状态与API Key列解析问题相互独立。在SSH恢复前，继续完成本机代码和旧服务器只读迁移评估，不伪造远端容器证据。

###### Java Reranker 有界分批修复计划（执行前）

1. 当前TEI以 `max-concurrent-requests=8`、`max-client-batch-size=16` 部署，但实测单个10候选请求会返回 `Model is overloaded`，说明“业务候选上限”和“单次传输批次”不能再共用一个配置。保留 `batchSize=16` 作为单次业务重排总候选上限，新增更小的 `requestBatchSize`，默认取已真实成功并保留容量余量的3。
2. 适配器在同一个30秒总deadline内按候选原序串行分批，禁止并行批次再次耗尽permit；每批解析并校验TEI局部index，映射为全局候选。Cross-Encoder每个query/document pair独立产生分数，全部批次汇总后按原始score统一降序、用原始候选序号稳定打破同分，再截取业务topK。
3. 任一批出现非200、超时、缺失/重复/越界index或非有限分数，整个重排失败并由既有领域层显式fallback；不混合“部分已重排+部分RRF”结果。总deadline包含本地Semaphore等待和全部HTTP批次，不能变成 `批次数 × timeout` 的隐式长尾。
4. 配置增加 `AI_RAG_RERANKER_REQUEST_BATCH_SIZE`，校验1～16且不大于业务 `batchSize`，脱敏摘要只输出数值。测试覆盖3候选单请求兼容、7候选按3/3/1分批、局部index映射和全局排序、后续批失败时整次失败、非法配置拒绝及敏感信息不泄漏。
5. 执行Java 17定向协议/配置测试和RAG相关回归，再重打包并重启本机8092。远端Reranker需先恢复到3候选稳定200；随后用同一真实查询做Top-10冒烟，只有10个实际重排候选且不降级才恢复1200条评分。

###### 旧 MySQL 迁移可行性阶段结果

- 旧机为7.5GiB RAM，已用约3.9GiB、available约3.2GiB、Swap已用42MiB，根盘30GiB中已用14GiB。业务 `mysqld` RSS约966MiB，另有XXL-JOB独立MySQL约432MiB；Kafka两个Java进程各约658–678MiB，Nacos约327MiB，MinIO约288MiB。旧机负载0.20/0.19/0.16，不是CPU打满。
- 本机8次ICMP：旧机平均77.712ms，新RAG机平均57.004ms；新机平均少20.708ms，但标准差31.669ms且有138.290ms尖峰。旧MySQL 12次新连接+`SELECT 1` 为0.500～1.600秒、平均0.683秒，公网握手和服务抖动明显。
- 业务库MySQL 8.0.46，库体仅15.30MiB/34表，buffer pool仅128MiB，max connections=151；运行6天累计3348连接、1117次Aborted_connects、15当前连接、2运行线程、16条slow query。数据体很小，迁移与校验成本低，但连接健康明显需要治理。
- performance_schema显示服务器内部多数配置/审计SQL均值为亚毫秒到数十毫秒，而一次完整检索观测到配置读取约0.27秒、审计0.8～2.5秒，证明多次公网往返是主要放大器；同时少数大chunk查询均值可达2.636秒、最大21.634秒，迁库不能替代SQL/载荷优化。
- 初步结论：只迁业务MySQL在容量上可行，预估增加约1GiB常驻内存；不迁XXL-JOB MySQL、Kafka、MinIO、Nacos和观测栈。由于新机模型服务当前过载且SSH不可用，尚不满足迁移实施门槛；恢复SSH和Reranker后再做受限从库演练，不能直接停旧库切换。

###### Java Reranker 有界分批实现与测试结果

- `RagProperties.Reranker` 现在把业务候选上限 `batchSize=16` 与HTTP传输批次 `requestBatchSize=3` 分离，后者可由 `AI_RAG_RERANKER_REQUEST_BATCH_SIZE` 配置；两者均限制1～16，并通过Bean Validation保证传输批次不大于业务上限，脱敏摘要只显示数值。
- `TeiRerankerAdapter` 在一个总deadline内串行执行3候选子批次；每批要求响应条数与请求完全一致，拒绝缺失、重复、越界和非有限分数。局部index映射成全局index后，按cross-encoder原始分数统一排序、原候选序号稳定打破同分，再截取业务TopK；任一子批失败都会使整次重排失败，不产生部分重排结果。
- 新增7候选按3/3/1分批、局部到全局映射、跨批全局排序，以及第二批429导致整次失败的协议测试；新增配置交叉约束测试。首次21项中配置9/9通过，协议12项因沙箱禁止绑定127.0.0.1全部启动失败；在允许本地端口的环境 clean 重跑后协议12/12、配置9/9通过。
- 扩大RAG回归时通配符误把3个私有 `Fixture` 内部类当测试，157个真实测试全部通过但构建被3个初始化错误标红；改为32个精确测试类后157/157通过，0 failure/error/skipped，BUILD SUCCESS 3.274秒。随后Java 17完整依赖打包成功，BUILD SUCCESS 8.065秒；打包前确认旧8092进程已经退出，没有覆盖运行中JAR。
- 下一步在提交后以该确切JAR启动8092，并用既有SciFact索引做真实Top-10冒烟。远端服务在连续过载后是否恢复仍需真实验证；代码测试通过不替代服务验收。

###### Java 分批后的真实 Top-10 验收结果

- 修复提交为 `252ae58 分批调用重排模型并统一候选排序`。新JAR在本机8092启动成功，PID=24955，Hikari建立新连接；运行参数保持Embedding batch=8、最多5次瞬态重试、600秒lease、30秒heartbeat，Reranker HTTP request batch明确为3。
- 使用既有SciFact索引、同一首条test query和 `hybrid_rrf_rerank` 目标做真实生产HTTP冒烟：响应 `code=0000`、`degraded=false`、无降级原因、10条引用；dense/sparse/fusion/rerank候选数为100/100/10/10，证明10个候选均实际进入分批重排，没有走RRF fallback。
- 该次耗时：embedding=7087ms、dense=2915ms、sparse=1590ms、rerank=15686ms、pipeline total=28895ms、configuration=311ms、hydration=1273ms、audit=1502ms、service=30399ms，客户端墙钟约31秒。正确性门禁通过，但CPU模型推理成为明确首要性能瓶颈；完整300×4评测预计持续数小时，不能用该单例延迟外推P95。
- 下一步启动新的空目录复评分并保留1200条原始记录。JWT刷新凭据只存在进程环境，不写入manifest/日志/Git；要求最终恰有1200个唯一variant/query、0错误、0降级，才生成可发布质量指标。

###### 完整复评分 Warmup 的 Qdrant 瞬态错误诊断计划（执行前）

1. run `scifact-quality-eval-21a5d1b` 在14条warmup后被主动中断：前三个query的四组均完成且Rerank未降级，第4个queryId=880的Dense与Sparse均出现 `RAG_QDRANT_UNAVAILABLE`。按warmup零错误门禁，不允许继续进入measured；目录和14条记录保留。
2. 先核验Qdrant `/healthz`、`/readyz`、collection状态及公网耗时，再对同一queryId=880的Dense/Sparse各重复3次，判断是稳定的查询数据/请求边界错误还是网络/服务瞬态错误。输出只含queryId、组别、稳定错误码、候选数和耗时，不输出查询正文。
3. 若同查询稳定失败，检查Qdrant请求体、filter、稀疏向量与响应边界并新增协议测试；若仅偶发连接/超时，则为Qdrant客户端增加仅针对连接异常和429/502/503/504的有限退避重试，总deadline有界，400/401/业务错误不重试。
4. 修复后先执行至少10轮同查询×Dense/Sparse无错误验证，再新建空输出目录完整复评分；旧失败run不覆盖、不与新run拼接。

###### Qdrant 瞬态错误诊断结果与客户端恢复计划（执行前）

- 中断评测后立即直连核验：`/healthz` 返回200/0.119秒，`/readyz` 返回200/0.139秒，目标collection读取返回200/0.161秒；Qdrant并未持续宕机。
- 对失败点 queryId=880 的Dense、Sparse各重复3次，6/6均返回业务码0000、`degraded=false`且10条引用。Dense业务耗时3.527～4.760秒、其中Qdrant 0.484～0.783秒；Sparse业务耗时4.066～4.224秒、其中Qdrant 0.946～1.372秒。由此判定为公网连接或Qdrant瞬态故障，不是稳定的查询数据、filter或向量格式错误。
- 本轮为Qdrant配置增加最大重试次数、首次/最大退避和单次操作总时限；默认最多2次重试，退避100ms～1s，总时限30s。单次HTTP timeout仍保持3s，防止某次连接独占全部预算。
- 客户端只重试连接/读写IO异常和HTTP 429/502/503/504；400、401、其他HTTP错误、响应过大、JSON/schema/业务响应错误均不重试。每次网络尝试独立获取并释放并发许可，退避期间不占permit；许可等待、全部请求与退避共同消费总deadline。
- 单元测试覆盖503后成功、持续503耗尽、401不重试和配置边界；随后执行Qdrant协议测试、RAG精确回归与Java 17打包。重启8092后至少完成10轮同查询Dense/Sparse真实验证，全部成功才重新开启1200条评测。
- MySQL迁移与该修复分开处理：仅业务MySQL保留为迁移候选；恢复新RAG服务器SSH、确认模型和Qdrant稳定并完成可回滚恢复演练之前，不进行正式切换，也不迁移Kafka、MinIO、Nacos、XXL-JOB数据库或观测组件。

###### Qdrant 有界恢复实现与真实门禁结果

- `RagProperties.Qdrant` 新增 `maxRetries=2`、100ms～1s指数退避和30秒 `totalTimeout`；单次HTTP超时仍独立配置。Bean Validation限制重试不超过5次、退避必须为正且最大值不小于初始值、总时限不得小于单次超时；`application.yml` 已提供对应四个环境变量，脱敏摘要不输出API Key。
- `QdrantVectorStoreAdapter` 现在复用一次序列化结果，仅对连接/读写IO异常和429/502/503/504执行有限重试；401及其他HTTP状态、非法JSON、响应过大、schema或业务响应错误均立即失败。每次网络尝试独立获取/释放Semaphore，退避不占permit；许可等待、请求和退避共享同一操作deadline，耗尽后返回明确的timeout/unavailable/http错误。
- 首次定向测试因测试数据扩展到14组键值后仍使用只支持最多10组的 `Map.of`，导致1个测试编译错误；改为 `Map.ofEntries` 后clean重跑成功。最终Qdrant协议9项、配置10项，共19/19通过，包含IO失败后成功、503后成功、持续503恰好3次、401恰好1次和联合边界。
- 扩大到33个精确RAG测试类后163/163通过，0 failure/error/skipped，BUILD SUCCESS 3.857秒；本轮文件 `git diff --check` 无问题。随后旧PID 24955已通过TERM优雅退出，Java 17六模块打包BUILD SUCCESS 7.142秒，新JAR由隔离启动脚本在8092启动，PID=41109，Hikari连接成功。
- 使用既有SciFact索引对失败点queryId=880做新版本真实门禁：Dense 10/10、Sparse 10/10均为业务码0000、`degraded=false`、10条引用。Dense首请求冷启动pipeline=11777ms，后续为2044～3065ms；Sparse为1695～2924ms。20次均未再出现Qdrant错误，满足重新启动完整复评分的前置条件。
- 门禁只对隔离benchmark tenant的唯一active password记录执行一次条件密码更新，影响恰好1行；随机密码和JWT仅存在进程变量，未写入文件、日志、manifest或Git。探测输出只记录variant、序号、业务码、降级状态、引用数和耗时，不记录查询正文或凭据。

###### SciFact 完整复评分恢复计划（执行前）

1. 以提交 `3ab790f` 和新空目录 `/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-3ab790f` 启动只读复评分，复用已完成且验证过7548向量块的四个既有target，不重新上传、摄取或修改profile。
2. 只对隔离benchmark tenant的唯一active password记录再次做影响1行的随机密码条件更新，登录后把JWT、用户名和密码仅放入评测进程环境，以现有401单次刷新机制覆盖长时运行；凭据不写入run产物或计划。
3. warmup固定10个查询×4变体。监控 `warmup.jsonl`，任一 `error`、`degraded=true`、空引用或重复variant/query立即停止；通过后才允许300×4 measured继续。
4. 完成后校验 `run.jsonl` 恰有1200行、300个query×4唯一组合、0错误、0降级、0空引用；再核验manifest中的prepared/targets hash与当前提交，并生成Recall@1/5/10、Precision@10、MRR@10、nDCG@10、MAP@10、Success@1/5/10和各阶段P50/P95/P99。
5. 评测期间保留本机JVM与远端中间件采样；若因基础设施失败中断，保留原目录并记录真实失败，不跨run拼接或外推指标。MySQL迁移不与本轮评分并行实施，避免改变实验环境。

###### Reranker 间歇降级追加诊断计划（执行前）

- 新run在第7条warmup首次观察到 `hybrid_rrf_rerank` 降级，停止信号发出时已落9条；0请求错误、0空引用、1次 `rerank_fallback`，没有measured文件。该run永久保留为失败证据。
- 先用正确API Key直连3候选合成请求并连续探测，区分远端再次过载/超时与Java响应校验问题；同时从应用日志按traceId定位，但不输出Key或查询正文。
- 若远端3候选也失败，在SSH仍不可用时不盲目重启容器；评估进一步降低 `requestBatchSize` 至1是否能以延迟换稳定性，并先做单query连续真实门禁。若远端成功而Java降级，则为安全日志增加稳定错误码并复现具体客户端分支。
- 任何修复仍需先追加计划、补单元测试、跑精确RAG回归、打包、真实连续门禁和中文提交；完整复评分重新使用新目录，不复用本次9条warmup。

###### Reranker 单批有限重试实现计划（执行前）

- 正确Key直连合成探测结果：3候选前3次200/0.235～0.522秒，第4、5次均45秒0字节超时；1候选前3次200/0.204～0.300秒，第4次同样45秒0字节超时，第5次200/0.322秒。由此排除“仅候选数超过容量”，确认服务存在与批次大小无关的间歇请求挂住；探测超时后的body标签来自上次临时文件，不作为响应内容证据，只采信curl状态000和0字节超时。
- `RagProperties.Reranker` 增加单次请求时限、最多重试次数和退避配置；现有 `timeout` 继续作为整次业务重排总deadline。默认单次请求10秒、最多2次重试、退避100ms～1s，总deadline仍30秒，并校验单次时限不大于总时限。
- 每个3候选子批独立在剩余总deadline内重试；仅连接/读写IO、HTTP 429/502/503/504可重试，401、其他HTTP状态、数量/index/score/JSON错误立即失败。Semaphore仍覆盖整个业务重排，避免重试放大并发；退避在同一deadline内完成。
- 协议测试新增单批超时/IO后成功、503后成功、401不重试、持续503有界耗尽及多批共享deadline的边界；配置测试覆盖绑定与非法组合。完成精确RAG回归、Java 17打包、连续真实Rerank门禁后再提交和启动新run。

###### Reranker 单批有限重试实现与验收结果

- `RagProperties.Reranker` 已增加 `requestTimeout=10s`、`maxRetries=2`、100ms～1s退避；原 `timeout` 保持整个业务重排总deadline，当前评测环境为30秒。配置强制单次时限不大于总时限、重试不超过5次、退避均为正且上限不小于初始值；四个新环境变量已进入 `application.yml`，摘要仍不泄漏Key。
- `TeiRerankerAdapter` 对每个3候选子批复用一次序列化body，在剩余总deadline内重试HTTP超时、连接/读写IO和429/502/503/504；401/其他HTTP、响应过大、非法JSON、数量/index/score异常不重试。整个业务重排继续只占一个Semaphore permit，重试不会扩大并发，全部子批与退避共享总deadline。
- 首次clean编译发现 `ObjectMapper.readValue(byte[])` 声明通用IOException；修正为在解析边界捕获IOException并转换成不可重试的 `RAG_RERANK_RESPONSE_INVALID`，避免错误地把坏JSON当网络抖动。随后协议14项、配置11项共25/25通过；完整33类RAG回归166/166通过，0 failure/error/skipped，BUILD SUCCESS 3.990秒。
- Java 17六模块打包BUILD SUCCESS 7.194秒，旧PID 41109已TERM退出，新JAR在8092以PID 53930启动，显式使用3候选、10秒单次、2次重试、30秒总时限，Hikari连接成功。
- 对queryId=880的 `hybrid_rrf_rerank` 连续真实请求10/10均业务码0000、`degraded=false`、10条引用；Rerank阶段耗时6744～16323ms，pipeline总耗时9309～22286ms。第6次Rerank为16323ms，明显跨过一个10秒单次挂起后由后续尝试恢复，且仍处于30秒总deadline内，直接证明真实瞬态故障恢复生效。
- 失败run `/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-3ab790f` 最终保留9条warmup、0 measured，其中1条Rerank降级；不与后续run合并。下一步提交本闭环后，以新提交号和全新目录再次启动完整复评分。

###### SciFact 复评分第三次恢复计划（执行前）

1. 使用提交 `44882f3`、当前PID 53930和新空目录 `/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-44882f3`；复用相同prepared数据、targets、四个profile及300条test query，唯一实验变化为Reranker瞬态重试。
2. 仍执行10 query×4 variant warmup并实时核验；出现任一error/degraded/空引用立即中断并保留目录。通过40条后继续1200 measured，期间不迁移MySQL或调整服务器环境。
3. 评测完成才运行行数、唯一键、错误、降级、空引用、hash和指标一致性核验；未完成前不发布任何Recall/MRR/nDCG/MAP或延迟结论。

###### Embedding 瞬态不可用诊断计划（执行前）

- 第三次run在检测时只有2条warmup并已立即中断。最初监控脚本误读不存在的 `.error` 字段而把失败行归类为“成功空结果”；核对原始JSON后，queryId=880 Dense实际为 `errorCode=RAG_EMBEDDING_UNAVAILABLE`、elapsed=31391ms、0候选，Sparse成功、sparse=100/fusion=10、9个唯一文档。后者由Top-10引用中同文档去重得到9个rankedDocumentIds，不是空引用或hydration少行。0 degraded、0 measured；目录 `/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-44882f3` 保留，不参与评分。
- 修正后续门禁统计为读取 `.errorCode`；先审计 `TeiEmbeddingAdapter` 的连接/超时重试和deadline语义，再用正确Key连续直连单query合成请求，判断是否与Reranker相同的间歇0字节挂起。
- 若当前30秒单次超时导致首次挂起直接消耗主要预算，则把Embedding改为“较短单次请求时限 + 有界总deadline”，仅重试超时、IO和429/502/503/504；响应格式、认证和确定性请求错误不重试。不能把业务失败伪装成空召回。
- 修复或配置调整必须有协议测试、完整RAG回归、真实重复门禁和新空run；继续坚持0 `errorCode`、0 degraded、0空rankedDocumentIds。

###### Embedding 有界总deadline恢复实现计划（执行前）

- 正确Key连续8次单文本直连中，第1和第6次均20秒0字节超时，其余6次200且0.215～1.399秒、响应9520 bytes；与正式run的31秒 `RAG_EMBEDDING_UNAVAILABLE` 形态一致，确认模型网关存在间歇请求挂住且随后自行恢复，不是输入或MySQL问题。
- 在 `RagProperties.Embedding` 新增 `requestTimeout=10s`；现有 `timeout` 从“每次尝试”明确为整个Embedding操作总deadline，评测环境保持30秒。已有最多5次重试和500ms～4s退避继续使用，但所有许可等待、HTTP尝试和退避必须受总deadline约束。
- 适配器复用一次序列化body，每次尝试按 `min(requestTimeout, remaining)` 设置HTTP timeout；连接/读写IO、HttpTimeout及429/502/503/504可重试，401/其他状态、响应过大、JSON、数量、维度和非有限数立即失败。每次尝试独立释放Semaphore，退避不占permit。
- 测试增加HttpTimeout后成功、IO耗尽、单次时限绑定/非法组合；保持既有429重试、401一次、超限、维度和中断测试。随后执行完整RAG回归、打包、真实连续Dense门禁和中文提交，再以新目录重启评分。

###### Embedding 有界恢复实现与验收结果

- `RagProperties.Embedding` 已增加 `requestTimeout=10s`，通用 `timeout` 明确为整个Embedding操作总deadline；评测运行值为30秒。配置校验单次时限必须为正且不大于总deadline，环境变量为 `AI_RAG_EMBEDDING_REQUEST_TIMEOUT`，脱敏摘要仅输出时限和重试次数。
- `TeiEmbeddingAdapter` 现在一次序列化请求，按剩余deadline动态收敛每次HTTP timeout；HttpTimeout、连接/读写IO和429/502/503/504可在总deadline内重试，401/其他状态、响应过大、JSON、数量、维度和非有限数立即失败。每次网络尝试独立获取/释放Semaphore，退避不占permit且也消耗总deadline。
- 新增真实HttpTimeout后第二次成功及连续IOException恰好3次耗尽测试；定向协议16项+配置11项为27/27通过。最终完整33类RAG回归168/168通过，0 failure/error/skipped，BUILD SUCCESS 3.892秒；本轮目标文件diff检查无格式错误。
- Java 17六模块打包BUILD SUCCESS 7.134秒，旧PID 53930已TERM退出，新JAR在8092以PID 63487运行，显式使用Embedding 10秒单次、最多5次重试、500ms～4s退避和30秒总deadline，Hikari连接成功。
- queryId=880 Dense生产链路连续10/10均业务码0000、`degraded=false`、10条引用；Embedding阶段129～424ms，pipeline总耗时1981～5480ms。本组未碰到新的10秒挂起，但直接公网8次探测已真实捕获2次20秒0字节超时，协议测试证明该异常会被有界重试，正式复评分继续作为长时间稳定性验收。
- 失败run `run-scifact-quality-eval-44882f3` 保留2条warmup、0 measured，其中Dense为 `RAG_EMBEDDING_UNAVAILABLE`，Sparse成功；监控字段已纠正为 `errorCode`，后续不会再把业务错误误判为空结果。

###### SciFact 复评分第四次恢复计划（执行前）

1. 使用提交 `1e9ebbf`、PID 63487和全新目录 `/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-1e9ebbf`；复用相同prepared、targets、profiles与300条query，冻结MySQL和远端部署，不引入其他实验变量。
2. warmup门禁读取真实字段 `errorCode`，要求40条中0 errorCode、0 degraded、0空rankedDocumentIds，且四个variant各10条；未满足即中断且不生成measured结论。
3. warmup通过后继续1200 measured并周期检查累计错误/降级/空结果；最终才生成质量与延迟指标，并核验300×4唯一组合、hash、组件候选计数和Rerank实际执行。

###### SciFact 复评分第四次运行进展（2026-07-19 19:07）

- PID 63487 的应用与 PID 66356 的评测器均持续运行；40条warmup已完整通过，四个variant各10条，`errorCode`、降级和空结果均为0。
- 正式评分已写入67/1200条，形成67个唯一variant/query组合；Dense/Sparse/Hybrid-RRF各17条、Hybrid-RRF-Rerank 16条，当前仍为0错误、0降级、0空结果。最新记录为queryId=1298的Hybrid-RRF，端到端8415ms、9个去重文档。
- 当前长耗时的直接原因是四种方案串行执行及远端CPU Embedding/Reranker的推理波动；7548是5183篇文档切块后的Qdrant子块总数，不是并发、线程或待执行任务数。业务MySQL容量约15.3MiB，其迁移只能改善跨公网配置、审计和hydration往返，不能消除模型推理的10～30秒间歇挂起。
- 为保持同一实验条件，本轮评分完成前不迁移MySQL、不调整远端资源。评分闭环后单独建立迁移前基线、备份恢复与回滚计划；不默认把Kafka、MinIO、Nacos、XXL-JOB或观测组件一并迁到RAG推理机，避免与模型抢占CPU、内存和IO。

###### 长跑评测期间的最终闭环缺口审计计划（执行前）

1. 不修改、重启或并发压测PID 63487及远端RAG中间件，只读核对阶段0～8的验收项、现有测试、浏览器留痕、真实运行产物和运维文档，形成“已证明/部分证明/缺失”的证据矩阵。
2. 重点核查三格式真实摄取、Agent回答引用、租户越权、任务取消竞态、质量消融、摄取与查询性能、资源采样、上线/回滚文档；不能用单元测试替代尚未执行的真实E2E，也不能把mini数据结论外推为SciFact结果。
3. 将不影响本轮评分的后续切片按依赖排序。评分未结束前只准备计划、fixture和离线验证，不触发模型、Qdrant、远程MySQL写入或服务器变更。
4. 评分完成并通过1200条门禁后，先固化质量报告与中文提交，再分别进入三格式摄取/Agent E2E、受控性能复测、数据库迁移演练和最终上线审计；每个切片仍单独先计划后执行。

###### 最终闭环缺口审计阶段结果（一）

| 验收域 | 当前权威证据 | 判定 | 后续闭环 |
|---|---|---|---|
| Java领域、强租户持久化、摄取状态机、外部客户端 | 迁移、领域/仓储/协议测试和当前168项精确RAG回归已通过；SciFact 7548子块真实索引并可检索 | 已证明核心实现；尚非最终上线审计 | 最终代码冻结后重跑相关模块及全量可运行测试，复核迁移/回滚 |
| PDF/DOCX/Markdown格式支持 | 三格式安全校验与解析协议有测试；PDF/DOCX曾直连Docling真实成功；生产HTTP全链摄取已真实完成Markdown | 部分证明 | 分别用唯一PDF、DOCX、Markdown经生产上传→Worker→READY→Qdrant→查询，核对MySQL/Qdrant版本和数量 |
| 取消、幂等、租约与副作用屏障 | 单元/持久化测试覆盖CAS、fencing、无租约取消；浏览器真实验证Markdown上传幂等和未领取任务取消 | 部分证明 | 增加运行中任务在外部调用前取消、租约接管和重建/删除的真实故障注入E2E |
| 检索、引用与Agent集成 | `RagContextContributor`把带`citation_id`的非可信参考注入模型调用前上下文，required错误fail-closed；检索调试和真实引用可回溯 | 代码与检索已证明，Agent回答尚未证明 | 绑定真实Agent/Workflow，完成流式与非流式回答E2E并核对回答中的citation与审计记录 |
| 前端租户管理员控制台 | 前端构建通过；桌面/移动浏览器验证知识库、上传、取消、状态反馈和检索调试布局 | 核心UI已证明 | 用三格式READY文档跑上传、查询、错误/空态及权限关键路径，保留最终截图/控制台证据 |
| 公开数据集与四组件消融 | SciFact官方数据已准备并完整摄取；当前第四次复评分warmup 40/40，正式记录运行中 | 未完成 | 必须1200条唯一组合、0错误/降级/空结果并由程序生成质量/阶段延迟指标，失败run不得拼接 |
| 性能与瓶颈 | mini真实并发1/2/4/10 pilot、分段计时和批量hydration前后对照已留痕；模型间歇挂起、远程MySQL往返已有实证 | 部分证明 | 补三格式摄取性能、当前SciFact查询分布、受控并发复测和资源同步采样；区分模型与数据库瓶颈 |
| 数据库与部署 | 现业务MySQL可用且容量很小；迁移可行性、备份/追平/回滚步骤已设计 | 迁移未执行且不是当前评分前提 | 评分完成后恢复新机SSH，先做受限恢复实例和前后基线；是否切换以资源竞争和实测收益决定 |
| 运维、API、评测与最终报告 | 主要事实集中在本计划及架构文档；计划约定的`docs/rag/`当前不存在，原始大产物主要在受控`/tmp`目录 | 未完成 | 固化脱敏API/运维/评测/容量/回滚文档、产物索引与hash，完成阶段8逐项上线审计 |

- 本轮审计没有把“存在代码/单测”替代真实E2E，也没有把mini小样本性能数字替代完整SciFact结论。下一优先级仍是保持当前评分不受扰动并完成1200条质量闭环。

###### RAG 运维与评测文档切片计划（执行前）

1. 在不触碰运行中应用、评测器和远端服务的前提下建立`docs/rag/`，以当前代码、`codex.md`、架构文档、计划执行记录和真实run manifest为事实源，不从记忆补写端点、参数或结果。
2. 编写入口README、运行配置/故障恢复文档和评测复现文档；账号、密码、API Key、Bearer和公网敏感信息只引用`codex.md`的受控凭据位置，不复制到新文档。
3. 评测文档固定记录数据来源、哈希、格式、线程/并发、warmup/measured、四组参数、指标定义、失败run隔离和最终1200条验收命令；当前质量数值保持“运行中”，待`metrics.json`完成后从程序产物回填。
4. 用源码和CLI帮助输出复核所有命令、环境变量与API路径，执行Markdown链接/格式检查；再以无会话背景的读者视角检查歧义、隐含前提和相互矛盾，修复后追加实际结果。本切片与完整评分结果一起形成中文本地提交，避免单独提交半成品数字。

###### 评测启动与复评分安全语义修复计划（执行前）

1. 修复本地隔离启动脚本只关闭Nacos discovery却仍导入/动态刷新Nacos config的问题：显式关闭Nacos config，保留命令行冻结的RAG/Worker/存储参数；当前运行中PID不重启，本次只做脚本语法/源码验证，真实启动回归放在完整评分结束后。
2. 修复`RAG_BENCHMARK_EXISTING_TARGETS`仍注册新租户并用新租户访问旧targets的逻辑错误：fresh run继续创建一次性隔离租户；existing-target evaluate模式禁止注册新租户，必须由调用方通过环境安全注入原租户用户名/密码并重新登录，以支持长时JWT刷新。缺少凭据立即失败，不能跨租户请求后再把404当检索结果。
3. 文档将服务端benchmark资源从“可复跑”改为“保留审计”；复评分需要同租户安全凭据或受控密码恢复，当前没有通用资源清理API。完整门禁限定evaluate模式的targets来源hash必须非null；fresh run只记录生成targets文件hash，不能执行`null==null`假校验。
4. 增加独立`score`重算：产物manifest必须绑定实际run/qrels SHA-256，四组质量JSON必须与Runner生成的`metrics.json`一致，queryCount=300且missingRunCount=0。完成shell语法、脱敏扫描和读者复测后追加实际结果。

###### RAG 运维文档与评测脚本安全语义阶段结果

- 新建`docs/rag/README.md`、`api.md`、`operations.md`和`evaluation.md`，明确Java业务代码与RAG服务器中间件职责、强租户/引用边界、管理API、配置/恢复/回滚、SciFact数据哈希、四组消融、质量与closed-loop性能方法；没有复制IP、密码、API Key或Bearer，凭据继续只指向受控`codex.md`。
- 评测门禁实际检查1200条唯一variant/query、四组各300、0 error/degraded/empty、Rerank候选与正耗时、prepared全部文件hash、targets来源hash和300条qrels覆盖；随后独立执行`score`，以run/qrels SHA-256绑定重算产物并要求四组质量JSON与Runner产物一致、queryCount=300、missingRunCount=0。Bash块启用`set -euo pipefail`，避免早期断言或管道失败被末尾成功命令掩盖。
- 三轮无背景读者测试依次发现并推动修复：模型网关明文HTTP安全遗漏、targets `null==null`假门禁、metrics未重算、Nacos config仍可刷新、旧targets跨租户复用、MySQL未显式选库、服务端资源并未可复跑，以及门禁未fail-fast。最终读者复测实证hash mismatch与上游jq失败均能传播非零状态，结论为“读者测试通过”。
- `start-local-rag-benchmark-app.sh`新增环境变量和命令行双重`spring.cloud.nacos.config.enabled=false`，继续关闭discovery；文档明确真实启动日志仍须证明没有远端导入。`run-local-rag-mini.sh`的existing-target模式不再注册新租户，强制从环境注入原租户用户名/密码；缺少任一项在任何认证/写入前稳定退出2。
- 两个脚本`bash -n`通过；本机没有shellcheck。缺少原租户凭据的负向验证输出稳定错误摘要、退出码2且未创建run目录；目标路径`git diff --check`和文档敏感值扫描通过。真实重启验证延后到当前评分终止后的独立切片，不用源码检查冒充Nacos隔离已运行通过。

###### SciFact 第四次复评分 Qdrant 失败诊断计划（执行前）

1. 第四次run在正式记录增长到227条时已立即TERM停止评测器；唯一失败为queryId=1278的Hybrid-RRF，耗时32368ms、`RAG_QDRANT_UNAVAILABLE`、0候选/0排名；其余226条无错误或降级。目录、run.jsonl和仍显示running的异常终止manifest原样保留，不手改completed、不参与聚合。
2. 保持PID 63487应用和远端环境不变，先直连核验Qdrant health/collection及连续最小查询的HTTP状态、耗时与0字节超时；再安全恢复同一隔离tenant认证，对queryId=1278依次做Dense、Sparse、Hybrid各至少10次真实debug，不输出查询正文或凭据。
3. 同时按失败时间窗读取应用稳定错误码/异常类型和Qdrant客户端重试路径，区分公网TCP/HTTP间歇挂起、服务429/5xx、单次3秒超时、总deadline、Semaphore等待或确定性请求/schema错误。只有直接证据支持时才改代码/配置。
4. 若是单次公网连接偶发超时且随后恢复，评估在不放大并发的前提下增加Qdrant重试机会、抖动退避或采用更短单次时限；所有尝试仍受单操作总deadline，401/4xx/schema不得重试。若相同query稳定失败，则修复请求/filter/响应逻辑而不是扩大超时。
5. 任一修复须补协议/配置测试、完整RAG回归、Java17打包及同query连续门禁，再使用新提交/新空目录重跑；本次227条永不拼接。MySQL迁移和并发压测继续冻结。

###### Qdrant 短单次尝试与更多恢复机会计划（执行前）

- 直接公网探测已证明Qdrant在线但间歇挂起：12次health均200、0.110～2.172秒；8次collection读取前7次200，第8次TCP已连但8秒0字节超时；16次同tenant/version精确count中14次200、1次8秒0字节超时、1次约5秒empty reply。不是queryId=1278的确定性filter/schema错误。
- 安全恢复原benchmark tenant密码后，同queryId=1278的Dense/Sparse/Hybrid各10次全部业务码0000、10条引用、无降级。Dense Qdrant阶段却从243ms波动到20799ms，证明连续挂起可被当前重试偶尔恢复，但仍有机会耗尽。
- 运行进程环境权威核验为：Qdrant单次timeout=10s、最多2次重试、100ms～1s退避、总deadline=30s。正式失败耗时32368ms与“首次+2次尝试各消耗约10s并耗尽总deadline”完全一致；启动脚本的10秒覆盖抵消了代码默认3秒单次的快速恢复设计。
- 本切片只把隔离benchmark启动脚本固定为单次3秒、最多5次重试、100ms～1s退避、总deadline30秒；最坏网络尝试与退避仍在总deadline内，重试串行且每次独立释放Semaphore，不增加并发。生产默认暂不因公网联调直接改变。
- 当前第四次run的Embedding batch=8、单次/总时限10/30秒、最多5次重试与500ms～4s退避，Reranker request batch=3、10/30秒、最多2次重试，以及Worker 600秒lease/30秒heartbeat此前由启动外层环境注入。为避免重启或他人复现时静默回到类默认值，隔离benchmark脚本同时显式冻结这些已验收参数，不改变本轮实验变量。
- 修改后先跑脚本语法、`RagPropertiesTest`和`QdrantVectorStoreAdapterProtocolTest`，再重启8092并从进程环境核对实际值；同query三组各10次必须0错误，随后追加至少30次直接count门禁。只有通过才中文提交并以新提交号开启第五次空目录复评分。

###### Qdrant 短单次尝试与运维文档切片执行结果

- 第四次run `/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-1e9ebbf` 已在首次业务错误后停止并原样保留：227条measured、227个唯一组合、1个错误、0降级、1个空排名。唯一失败为queryId=1278的`hybrid_rrf`，耗时32368ms、错误码`RAG_QDRANT_UNAVAILABLE`；异常终止后的manifest仍为`running`，不手工伪造完成状态，也不与后续run拼接。
- 公网只读探测证实故障位于Qdrant链路：health 12/12返回200（0.110～2.172秒）；collection读取7/8返回200，第8次TCP连接后8秒0字节超时；同租户/版本精确count 14/16返回200，另有1次8秒0字节超时和1次约5秒empty reply。相同query的Dense/Sparse/Hybrid各10次均业务码0000、10条引用且无降级，但Dense的Qdrant阶段波动至20799ms，排除固定filter/schema错误和MySQL原因。
- 隔离评测启动脚本现固定Qdrant单次3秒、最多5次重试、100ms～1秒退避和30秒总deadline，并显式冻结此前已验收的Embedding、Reranker、Worker参数；Nacos config/discovery均以环境变量和命令行关闭。新应用PID 21567在8092启动，进程环境逐项核对与脚本一致；19:42启动时间窗没有新的Nacos远端配置加载记录，只有日志适配器初始化信息。
- queryId=1278在新配置下Dense/Sparse/Hybrid各10次均成功、每次10条引用且无降级；其中Hybrid一次Qdrant阶段14176ms仍由有界重试恢复。随后连续30次直接filtered count全部HTTP 200，耗时约0.216～0.601秒。
- 正确定向测试为`RagPropertiesTest` 11项和`QdrantVectorStoreAdapterProtocolTest` 9项，共20/20通过；最终选定32个RAG测试类共173/173通过，0 failure/error/skipped，BUILD SUCCESS 3.928秒。此前一次因协议测试类名写错只运行11项配置测试，不作为最终验收数字。
- 两个脚本`bash -n`通过；existing-target模式缺失原租户凭据时在认证或写入前退出2且不创建run目录。本机未安装shellcheck，未把缺失工具冒充检查通过。
- 本轮没有修改Java源码，当前JAR仍对应代码提交`1e9ebbf`；脚本、文档和本计划将在本地中文提交后，以该新提交号和全新空目录启动第五次独立复评分。
- 关于迁移决策：7548是5183篇SciFact文档切分后的Qdrant子块数。现有MySQL约15.3MiB，迁移可改善跨公网配置、审计与hydration往返，但不能修复Qdrant/模型公网链路的间歇0字节挂起；第五次同条件评分期间继续冻结迁移，闭环后再用迁移前后基线决定是否切换。Kafka、MinIO、Nacos、XXL-JOB和观测组件不默认迁到RAG推理机，避免与Qdrant和CPU模型争抢资源。

###### SciFact 第五次独立复评分计划（执行前）

1. 以本地提交`2796bdc`、当前8092应用PID 21567和全新空目录启动；复用同一prepared与原隔离租户targets，四个variant、300条query、10条query warmup等实验条件不变。应用JAR虽仍对应Java源码提交`1e9ebbf`，但`2796bdc`只改变隔离启动/评测脚本和文档，manifest按当前完整工作树提交记录；进程环境另行作为运行配置证据保存。
2. 只在单个受限shell中为原benchmark用户生成临时随机密码、更新密码摘要、登录并把凭据注入评测进程；不在命令输出、计划、日志或Git中留下明文。existing-target脚本必须证明没有新注册租户。
3. warmup落满40条后要求四个variant各10条、0业务错误、0降级、0空排名；任一不满足立即终止并保留失败run。通过后继续1200条measured，运行期间周期核验唯一组合和错误/降级/空结果，不修改远端服务、不迁移MySQL、不做并发压测。
4. 只有1200条全部完成后才执行完整性/hash/组件实际执行门禁和独立`score`重算，生成四组Recall、MRR、nDCG等质量指标及阶段延迟分布；失败run永不参与最终报告。

###### SciFact 第五次启动与 MySQL 断链结果

- 首次目录`run-scifact-quality-eval-2796bdc`只生成`running` manifest与targets后，评测进程随非交互父会话退出；没有warmup或measured请求。目录原样保留为启动中断证据，不计作质量运行。
- 托管会话在新目录`run-scifact-quality-eval-2796bdc-r2`启动后，warmup写入18条、0 measured；门禁捕获1个错误、0降级、1个空排名后立即停止。失败记录为queryId=716的`hybrid_rrf_rerank`，评测端耗时66592ms、`RAG_BENCHMARK_API_FAILED`。
- 同一请求trace的服务端日志给出确定根因：Rerank前后链路已运行，随后`RagRepository.listChunksByIds`调用`queryListByTenantAndChunkIds`批量hydration时，MySQL连接在最后收发约52.7秒后以0字节EOF断开，抛出SQLSTATE `08S01`/`Communications link failure`；HTTP观测总耗时66585ms。约81秒后，独立的Worker到期任务扫描又在另一连接上发生相同断链，排除单个query或单条SQL的确定性错误。
- 因此旧MySQL公网链路现已被实证为第二个稳定性瓶颈；此前“迁移只能改善往返、不是当次Qdrant失败根因”的判断仍适用于第四次run，但不再适用于本次第五次warmup失败。`-r2`永久不参与聚合。

###### 业务 MySQL 迁移至新服务器计划（执行前）

1. 先只读核对新RAG服务器的CPU、内存、Swap、磁盘、容器资源上限/实时占用、端口和Docker网络；同时核对旧MySQL版本、字符集/时区、库大小、表/行数、账号权限和关键全局变量。若新机没有足够的常驻内存/磁盘余量，则不强行同机迁移，改为先优化连接与网络或另选数据库节点。
2. 若容量允许，只部署固定版本MySQL与独立数据卷/配置/健康检查/资源上限，不上传Java/Vue源码，不改Qdrant collection和模型容器；端口仅对本地开发机当前公网来源或受限规则开放，创建最小权限应用账号。
3. 对旧库执行事务一致性逻辑备份，记录库级/表级结构、行数与备份SHA-256；在新实例恢复后核验表数、逐表行数、关键租户/RAG对象数量、字符集与约束。旧库保持只读可用作为回滚源，不做删除。
4. 迁移切换前停止8092应用，避免评测/Worker写入；恢复校验后只通过本地进程环境切换数据库地址，重新启动并执行认证、知识库/targets、配置、Dense/Sparse/Hybrid/Rerank真实查询及连接稳定性门禁。失败立即停新应用并切回旧库。
5. 切换成功后先做串行真实门禁与MySQL连续探测，再以全新提交/空目录重启SciFact；迁移前后的数据库阶段延迟和断链率分别留痕。Kafka、MinIO、Nacos、XXL-JOB、Grafana/日志栈暂不迁移，除非后续资源和链路数据证明收益大于与模型/Qdrant争用风险。

###### MySQL 迁移公网直连阶段结果与自动隧道计划（执行前）

- 新机容量核验为15 GiB内存、约9.9 GiB可用、26 GiB空闲磁盘；MySQL 8.0.46固定镜像已部署为1 CPU/768 MiB/256 PID、256 MiB Buffer Pool，容器healthy。首次因非敏感配置误设600导致容器内mysql用户不可读，初始化未开始；改为644后正常初始化，密钥env始终600。
- 迁移期间8092已停止且无评测器。事务一致性dump完整结束，大小93443397 bytes、SHA-256 `ffc2bae94a16d4d68c7a468bb63f28e3ec8ba91e54b8c5c1a6f12b26d8e86aba`、34张表；新旧逐表精确行数一致，总计45374行，其中`rag_chunk=38295`、`rag_retrieval_citation=5661`、`chat_message=163`。旧库未删除。
- 新库公网先以DOCKER-USER只允许当前开发网络`223.104.79.0/24`并要求TLS；初次按浏览代理IP放行被正确阻断，服务器抓包确认终端经运营商NAT实际为同/24的另一地址后修正。公网真实queryId=716 Hybrid+Rerank成功、10引用、无降级，总耗时27979ms，数据库相关配置/hydration/audit约2.8秒。
- 随后的脚本重启在MySQL TLS握手阶段再次出现15秒0字节`SocketTimeout`，证明同一新服务器的公网链路仍存在与Qdrant/模型类似的间歇挂起；迁移服务器不能单独消除该故障。当前状态不得宣称迁移闭环。
- 下一步增加由benchmark启动脚本自动确保的持久SSH本地转发：使用已核验host key和本机SSH key，把本机`127.0.0.1:13306`转到RAG服务器`127.0.0.1:3306`，开启ExitOnForwardFailure/ServerAlive并校验本地监听及MySQL握手；应用JDBC改连本地端口并继续`sslMode=REQUIRED`。用户无需手工建立隧道。
- 先为RAG服务器安装本机公钥并验证BatchMode；实现幂等启动、陈旧PID/端口冲突fail-fast和不输出凭据。连续数据库与真实检索门禁通过后，删除公网3306发布和DOCKER-USER放行，重启/SSH断线恢复测试通过才视为迁移完成；失败则切回旧库。

###### MySQL 自动隧道与应用切换阶段结果

- RAG服务器原`sshd -T`明确为`pubkeyauthentication no`；本机ED25519公钥写入后仍只广告password。已备份`/etc/ssh/sshd_config.pre-key-auth-20260719`，只把`PubkeyAuthentication`改为yes，`sshd -t`通过后reload；BatchMode公钥登录实测成功，密码登录仍保留作回滚。
- 新增LaunchAgent `cn.bugstack.ai.rag-mysql-tunnel`，以`KeepAlive`维护`127.0.0.1:13306 → RAG-Server 127.0.0.1:3306`，固定BatchMode、ExitOnForwardFailure、10秒连接时限和15秒ServerAlive；幂等ensure脚本由应用启动脚本自动调用。首次前置SSH探测可能在认证前公网卡住，已删除该同步阻塞点，交由LaunchAgent后台重连并用20秒本地端口门禁判定。
- 隧道建立后30/30次独立TLS数据库连接和`tenant`计数全部成功：min 561ms、mean 995.6ms、P95 1421ms、max 2781ms。强制`launchctl kickstart -k`后PID从69644切换为73430，本地端口自动恢复，数据库计数仍为22。
- 第一次用新脚本启动时出现`Access denied`，根因不是数据库：`read_table_cell '| MySQL |' 5`先匹配到组件资源表，把35字符的资源描述当密码；进程值与9字符真实凭据不一致。脚本已改为只匹配“root或应用配置中的数据库用户”凭据行，随后进程密码等值核验通过。
- 修正后应用PID 71769通过自动隧道启动，Hikari成功建连；实际进程环境为本机13306、TLS REQUIRED、连接/Socket时限5/15秒，连接池min/max为1/6、idle/maxLifetime/keepalive为120/600/60秒。queryId=716的Hybrid+Rerank再次真实成功，10引用、无降级、服务端总耗时19216ms。
- 用户随后使用数据库客户端公网直连，服务端已识别账号但客户端未启用安全连接，返回`caching_sha2_password`要求secure connection。当前按用户直连需要暂保留公网3306，仅允许`223.104.79.0/24`且强制客户端`SSL Mode=REQUIRED`；项目运行仍默认自动隧道。公网关闭项延后到用户确认不再需要直连，不能把计划目标误记为已执行。

###### SciFact 第六次独立复评分计划（执行前）

1. 以配置/脚本提交`5e49f30`、当前8092应用PID 71769和全新空目录`/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-5e49f30-r6`启动；应用JAR对应Java功能提交仍单独记录，不把配置提交冒充JAR源码提交。复用已校验的prepared、同一隔离租户及四个既有targets，不重新摄取或修改Qdrant数据。
2. 通过自动SSH隧道连接新MySQL，只对用户名、用户ID、租户ID同时匹配且唯一active的原benchmark用户做一次条件密码摘要更新；随机明文仅存在于当前受限shell及子进程环境，不写计划、日志、manifest或Git。更新影响行数不等于1即停止。
3. 固定10个query×4个variant的40条warmup，load关闭、单请求上限120秒。预热门禁要求四组各10条、唯一组合40、0业务错误、0降级、0空排名；任一条件不满足立即终止进程并原样保留失败run，禁止进入measured。
4. warmup通过后才允许继续300个query×4个variant的1200条measured；运行中周期检查唯一组合、错误、降级、空结果、8092存活及新MySQL断链日志，不变更远端服务、索引、模型参数或并发度。
5. 只有1200条完整且所有门禁通过后才执行独立`score`重算和hash绑定，产出Recall@K、MRR@K、nDCG@K、错误/降级率及分阶段延迟。该轮提交前产生的计划追加将在运行结果明确后与结果一起中文提交。

###### SciFact 第六次预热门禁执行结果

- MySQL切换与自动隧道闭环已由提交`5e49f30 迁移业务MySQL并增加自动安全隧道`固化；提交只包含启动脚本、ensure脚本、LaunchAgent plist和本计划，未暂存运行日志或用户其他未跟踪文件。`codex.md`按仓库约定由`.git/info/exclude`排除，服务器与凭据说明已在本机受控文件更新但未强行纳入Git。
- 启动前核验8092应用PID 71769仍存活、本机13306隧道可连、没有其他benchmark evaluator；prepared四个生成文件hash与其manifest一致。targets SHA-256为`70273080edaa9507a03c4cb9e14099505034349c4cdcc07e7cc12e1eccfe5ffd`，CLI JAR SHA-256为`fde246d77f5b457216b1851feb3691302ef1488cefa143a393750c6d5a597b0d`，App JAR SHA-256为`e6d59dde66e35af42610446983739d6ace566fed6b5eb3a4f698ffb8ef044cfb`。manifest中的`codeRevision=5e49f30`只代表启动时工作树提交，不冒充两个JAR的构建revision。
- 通过新MySQL的TLS SSH隧道对原隔离benchmark用户做用户名、用户ID、租户ID、secret类型、active状态与未删除条件更新，实际影响严格为1行；随机密码和JWT只存在于托管shell/子进程环境，未输出或落盘。
- 第六次run使用全新目录`/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-5e49f30-r6`，保持warmup query=10、四个variant、load关闭、请求上限120秒和原targets。历史成功/失败目录均未删除、覆盖或拼接。
- 预热最终恰好40条：Dense/Sparse/Hybrid-RRF/Hybrid-RRF+Rerank各10条，40个唯一variant/query组合，0业务错误、0降级、0空`rankedDocumentIds`；10条Rerank组均有正候选数和正`rerankMs`。门禁命令退出码0，随后正式measured开始写入。
- 当前只证明“迁移后预热稳定性门禁通过”，不代表1200条正式质量评测完成；评测继续由前台托管会话运行，任何业务错误、降级、空结果或重复组合仍将触发停止并保留失败证据。

###### SciFact 第六次正式阶段认证失败结果与诊断计划（执行前）

- 预热通过后正式阶段写入3条，其中前2条成功，第3条queryId=880、variant=`hybrid_rrf`返回`RAG_BENCHMARK_HTTP_401`并产生空排名；发现后立即向托管前台会话发送SIGINT，进程退出码130。第六次目录保持40条完整预热与3条正式记录原状，不修改manifest状态、不参与最终聚合。
- 只读复核此前`run-scifact-quality-recover-dba0df8`发现：它虽然manifest标记completed且有1200个唯一组合，但包含1123个HTTP 401、1123个空结果和20个降级；其中四组各300只是形状完整，不满足质量有效性。其`metrics.json`不能作为成功基线或最终指标，之前将其称为“唯一完成run”只能表示进程写满，不表示验收通过。
- 下一步对照当前benchmark CLI源码与实际JAR SHA/构建时间/类字节码，确认401后重新登录逻辑是否已经打入运行JAR，以及刷新触发是否覆盖正式阶段；同时只读检查该隔离用户的password与refresh-token记录数量、状态、过期时间及服务端认证日志，不输出hash、token或明文。
- 若源码已有修复但CLI JAR陈旧，则只重建benchmark模块并以JAR SHA留痕，不改变应用JAR、远端索引或模型参数；若源码逻辑有缺陷，先补可重复的CLI单测，覆盖“首请求401→重新登录→同请求只重试一次→成功”和“重试仍401→明确失败”，禁止无限刷新。
- 修复后先用短寿命或确定性stub完成认证回归，再恢复隔离账号并针对单个query连续跨越当前JWT有效期做真实门禁；只有确认自动重新登录生效，才以新的提交/JAR SHA/空目录启动第七次40+1200复评。

###### 伪401与SSH隧道半失活根因、修复计划（执行前）

- 实际CLI JAR包含`RefreshingLoginTokenProvider`，字节码明确在首次401后重新登录、替换Authorization并只重放一次；第六次manifest也记录`refresh=enabled`。服务端在失败窗口记录了刷新登录成功，且JWT access有效期为7200秒，因此不是“刷新代码未打包”或五分钟正常过期。
- `AuthFilter`当前把JWT解析、身份写入和`filterChain.doFilter`放在同一个广义`catch (Exception)`内；下游Hikari/MySQL异常因此被错误改写为HTTP 401。CLI收到伪401后按设计重新登录并重放，重放再次撞到数据库断链，最终才记录`RAG_BENCHMARK_HTTP_401`。
- 同一trace的权威日志显示Hikari连接closed、`CannotGetJdbcConnectionException`、SQLSTATE `08S01`和`Communications link failure`；经13306执行10次独立TLS `SELECT 1`出现约4.8～65.2秒延迟且至少1次5秒连接失败，新SSH命令本身也长时间无输出。LaunchAgent仍为running、端口仍监听，证明原`nc`门禁只能证明监听，不能证明转发可用。
- 代码修复一：收窄`AuthFilter`捕获边界，只把缺失/非法/过期JWT映射401；有效JWT进入下游后，过滤链异常必须原样传播给全局技术异常处理，`finally`只清理SecurityContext与TenantContext。补无效JWT仍401、有效JWT+下游异常不被改写、上下文必清理的定向测试。
- 代码修复二：`ensure-rag-mysql-tunnel.sh`改用真实MySQL TLS `SELECT 1`作为健康门禁；端口存在但查询失败时先`kickstart -k`重建而不是把半失活判健康。数据库凭据从受控`codex.md`读取且不打印；缺少mysql客户端/凭据时fail-fast，避免退回弱`nc`假阳性。
- 验证顺序：先定向Java测试与shell/plist语法，再强制制造下游异常验证HTTP不再伪401；随后重建隧道并做至少30次真实数据库查询、覆盖keepalive/空闲周期。若底层公网链路仍导致查询失败，则不扩大超时掩盖，转为评估用户态WireGuard/本地评测只读副本，并把生产链路与质量评测环境分别留痕。

###### 伪401修复与真实隧道门禁执行结果

- `AuthFilter`现将身份解析/构造与`filterChain.doFilter`拆成两个异常边界：只捕获认证阶段`RuntimeException`并写401，下游`ServletException`/`IOException`/运行时技术异常原样传播；成功或异常结束都在`finally`清理Spring Security与租户上下文。
- 新增`AuthFilterTest`覆盖非法JWT不进入下游且返回401、有效JWT下游抛`ServletException`必须原样传播且响应不变为401、成功下游调用前身份/请求属性可见且调用后上下文清理。首次测试编译因误用不存在的`generateAccessToken`失败，改为项目实际`generateToken`；第二次因app模块仍使用旧Surefire/JUnit4 runner，把新增测试由JUnit5改为JUnit4。最终Auth/JWT/Trace过滤器共8/8通过，0 failure/error/skipped。
- `ensure-rag-mysql-tunnel.sh`不再用`nc`监听判活；现在要求本机mysql与perl，在5秒进程硬上限、3秒connect timeout内通过`ssl-mode=REQUIRED`的真实`SELECT 1`。已有服务健康则直接返回；查询失败即打印stale摘要并`kickstart -k`，最多10轮真实数据库门禁，凭据只从环境或受控`codex.md`读取且不输出。
- 链路恢复窗口中先做15次查询，空闲65秒跨越keepalive周期后再做15次：30/30成功，min约597ms、max约3478ms。随后对LaunchAgent SSH PID 73430发送STOP模拟“进程活着但转发停滞”，新门禁在硬超时内识别stale并重建，PID变为3450，tenant计数22成功，证明旧`nc`假阳性已消除。
- Java17全模块跳过重复测试打包成功，旧PID 71769先TERM退出；新App JAR由隔离脚本在8092启动，PID 4101，Hikari经真实门禁后的13306成功建连。新App JAR SHA-256为`c3bcd08910a52f0653663bfd89a14ac3275d08fbf8ab37f4924b9da7dec30af9`，重组后的CLI JAR SHA-256为`12036b795f3dcdf149c4da9468407a78741ad0ad0fd4f32ba60edd3f0582ead6`。
- 真实故障注入先恢复隔离用户并成功登录，再STOP当前隧道、以有效JWT调用原Hybrid目标；请求在数据库通信失败后由新JAR明确返回HTTP 500、curl退出0，没有伪401或认证重登。第一次采集脚本误用zsh只读变量`status`，HTTP请求已产生服务端`ServletException/RecoverableDataAccessException`证据但shell打印阶段失败；改用`http_code`完整复测得到`forced_downstream_http_status=500`。两次trap均尝试恢复，最终ensure和TLS查询通过。
- 本轮修复了错误分类和半失活发现，不声称消除了公网传输抖动；此前实测仍出现4.8～65.2秒及连接失败。第七次复评分前必须重新通过连续数据库/真实检索门禁，正式run任一5xx/降级/空结果仍立即停止。

###### 质量评测专用本地 MySQL 副本计划（执行前）

1. 目的不是把生产数据库“迁回本机”，而是把SciFact检索质量/组件消融与已证实的公网MySQL传输抖动解耦；远端新MySQL保持原状，继续作为部署、远程性能和可靠性评测对象。最终报告必须分别标注本地副本质量环境与远端链路性能环境，禁止混写延迟。
2. 本机无Docker，但已有Homebrew MySQL 9.6.0_2、10 CPU、32 GiB内存、759 GiB空闲磁盘。使用独立`/tmp/ai-agent-rag-benchmark/mysql-data`数据目录、loopback `127.0.0.1:13307`、独立socket/pid/log和LaunchAgent；关闭binlog/performance_schema，限制连接与Buffer Pool，不占用系统默认3306，不启用公网监听。
3. 只从已完成的迁移dump `/tmp/ai-agent-scaffold-mysql-20260719T2016.sql`初始化一次，执行前再次要求SHA-256为`ffc2bae94a16d4d68c7a468bb63f28e3ec8ba91e54b8c5c1a6f12b26d8e86aba`；创建最小权限`ai_agent_app@127.0.0.1/localhost`，应用密码仍从受控`codex.md`读取，不写入脚本、plist、日志或Git。
4. 恢复后核验34张表、45374行及关键表计数与迁移基线一致，并验证目标tenant、四个targets、7548向量块元数据均存在。若MySQL 9.6导入与8.0 dump不兼容，则停止并保留错误证据，不修改dump迁就。
5. `start-local-rag-benchmark-app.sh`新增显式`RAG_BENCHMARK_LOCAL_MYSQL=true`分支：true时确保本地副本并连13307/非TLS loopback，false默认仍确保SSH隧道并用13306/TLS。不得依据端口隐式猜测环境，manifest/计划记录实际数据源。
6. 完成脚本语法、LaunchAgent lint、真实重启恢复、30次本地查询、Auth/RAG回归和新JAR启动；再对同一query四组真实冒烟。全部通过后中文提交，以新提交/JAR SHA和全新目录开启第七次40+1200质量复评。

###### 公网 MySQL 客户端连接失败诊断计划（执行前）

1. 本轮只做只读诊断，不修改业务数据、不切换8092应用数据源、不上传本地项目；分别验证本机到`103.205.240.84:3306`的TCP可达性与TLS握手，区分“10秒网络超时”和“已到达MySQL但认证方式不安全”两个阶段。
2. 经SSH只读核对远端MySQL容器健康、3306发布/监听、`require_secure_transport`、TLS证书状态、应用账号认证插件与允许来源Host，并核对DOCKER-USER/主机防火墙当前是否仍只允许此前识别的开发公网网段。
3. 给出数据库客户端可直接填写的主机、端口、SSL Mode及驱动兼容参数；若当前公网出口已经变化，只报告精确阻断点和最小修复方案，不擅自扩大到全网放行，也不降低`caching_sha2_password`或关闭TLS。

###### 公网 MySQL 客户端连接失败诊断结果

- 本机到`103.205.240.84:3306`的原始TCP握手在10秒内成功；远端`rag-mysql`容器为healthy，Docker确实发布`0.0.0.0:3306->3306/tcp`。`DOCKER-USER`当前只允许`223.104.79.0/24`访问3306后丢弃其他来源，本次服务端识别的客户端地址为`223.104.79.125`，因此本次不是防火墙网段不匹配。
- MySQL服务端`have_ssl=YES`并支持TLS 1.2/1.3；`ai_agent_app@%`与`root@%`均使用`caching_sha2_password`。服务端全局`require_secure_transport=OFF`，但用户客户端没有建立安全连接且其驱动又不允许在明文链路获取RSA公钥，所以报`Authentication requires secure connection`；这不等于密码错误。
- 用公网地址、`ai_agent_app`、已知数据库密码和`SSL Mode=REQUIRED`执行真实`SELECT 1`成功；同一密码用于`root`则明确返回1045 Access denied。因此客户端应使用应用账号，不能把应用密码当作公网root密码。
- 公网链路仍有抖动：连续TLS认证中出现一次接近硬上限才成功、下一次12秒硬超时，复现了客户端“当前10秒超时”提示。最小可用配置为TLS REQUIRED并把连接超时提高至30秒（必要时60秒）；项目自身继续使用SSH隧道/本地评测副本，不把提高超时冒充链路瓶颈已消除。

###### 本地质量副本门禁与 Rerank 降级诊断计划（执行前）

1. 核验8092进程环境明确连接本机`127.0.0.1:13307`，对本地副本执行表数、关键表计数、应用账号查询和LaunchAgent强制重启恢复；所有远端性能数据与本地质量环境继续隔离标注。
2. 对原SciFact隔离租户只条件更新唯一active密码凭据，随机明文仅存于受限临时目录/进程环境；依次调用Dense、Sparse、Hybrid-RRF和Hybrid-RRF+Rerank，要求HTTP 200、10引用、无降级，且Rerank有正候选数和正耗时。
3. 若前三组成功而Rerank降级，读取响应/审计中的结构化降级原因，并只读核对`rag-reranker`、`rag-model-gateway`健康、资源、容器日志和服务器内网直连；先区分模型进程故障、网关超时和公网传输抖动，再决定最小恢复操作。
4. 恢复后重新跑完整四组，不能用一次Rerank成功与前三组旧结果拼接门禁。通过后再执行脚本语法、plist lint、强制重启与30次本地查询，追加真实结果并中文提交本地质量评测环境。

###### 本地质量副本门禁与 Rerank 降级诊断结果

- 8092原PID 11511的实际进程环境确认`RAG_BENCHMARK_LOCAL_MYSQL=true`、`MYSQL_HOST=127.0.0.1`、`MYSQL_PORT=13307`，JDBC为loopback非TLS且显式允许本机`caching_sha2_password`公钥交换；本地MySQL LaunchAgent运行。恢复基线为34张表、导入dump SHA-256 `ffc2bae94a16d4d68c7a468bb63f28e3ec8ba91e54b8c5c1a6f12b26d8e86aba`，关键计数`rag_chunk=38295`、`rag_retrieval_citation=5661`、`chat_message=163`、`tenant=22`，四个SciFact target binding、一个知识库及四个profile均存在。后续真实冒烟会新增审计/引用/refresh token，因此总行数在45374导入基线之上增长是预期写入，不能再要求运行态总行数恒等45374。
- 第一次Dense请求实际成功，HTTP 200、10引用、100候选、无降级、`totalMs=32299`；门禁shell误用jq `false // "missing"`，把合法布尔false当缺失后退出，未错误宣称服务失败。改为显式`has("degraded")`后完整重跑，Dense/Sparse/Hybrid分别成功且无降级，但Rerank返回10引用同时`degraded=true`、`rerankCandidateCount=0`、`rerankMs=0`，结构化原因是`rerank_fallback`，因此该轮正确判失败。
- 远端容器证据：`rag-reranker`与`rag-model-gateway`均healthy、0次重启、无OOM；Reranker约占2.008 GiB/3 GiB，服务器仍有约9.5 GiB available、无Swap使用。网关在失败窗口记录同一Java客户端多次`/rerank`，部分200、部分499；模型端成功子批耗时约2.72～5.26秒并含0.83～1.47秒队列，证明模型在线，根因是Top-10按3/3/3/1串行时，公网响应抖动使10秒单批/30秒总deadline提前取消，而不是模型崩溃或数据库故障。
- benchmark启动脚本把Rerank参数改为可由环境覆盖，默认仍保持3候选有界子批与最多2次重试，但单批时限由10秒调到20秒、四子批共享总deadline由30秒调到60秒。此改动只作用隔离评测启动脚本，不冒充线上生产默认；延长单次等待可避免已在服务端完成的推理因公网响应略超10秒被取消并重复计算。
- 旧PID 11511优雅TERM退出，新PID 27659继续连接本地13307启动，实际Rerank环境为`requestBatchSize=3`、`requestTimeout=20s`、`timeout=60s`、`maxRetries=2`。同一query重新完整执行四组，依次为Dense `1981ms/100+0`、Sparse `1813ms/0+100`、Hybrid `4004ms/100+100`、Hybrid+Rerank `14988ms/100+100`；四组均HTTP 200、code 0000、10引用、0降级，Rerank真实`candidateCount=10`、`rerankMs=14061`，完整门禁通过。
- 强制`launchctl kickstart -k`后本地MySQL PID由10442切换为28980，ensure真实查询恢复；应用账号30/30次loopback查询均返回22个租户，8092应用PID仍为27659并在数据库重启后完成login+`/auth/me`，证明Hikari连接池恢复。本地数据库准备/健康脚本语法、plist lint、可执行权限和dump hash均通过；全仓`git diff --check`只命中未纳入本次提交的运行日志既有尾随空格，后续提交门禁必须对拟提交文件做作用域检查。

###### Benchmark 内建预热门禁实现计划（执行前）

1. 当前`RagBenchmarkRunner`在`runWarmup`写完后无校验就立即创建正式`run.jsonl`，外部轮询只能在正式阶段已开始后抢占中断，不能证明“门禁通过前零正式样本”。在`run`与`evaluate`两条路径中统一增加内部预热门禁，warmup=0保持显式跳过。
2. 门禁要求预热记录数严格等于`min(warmupQueries, queryCount) × 4`、variant/query组合唯一且四变体计数相同、无`errorCode`、无降级、排名非空；`hybrid_rrf_rerank`还必须有正`rerankCandidateCount`和正`rerankMs`，防止Rerank fallback伪装成有引用的成功响应。
3. 任一条件失败抛出带稳定错误码/摘要的异常，由既有manifest失败路径留痕；异常摘要只给计数，不包含query文本、token或凭据，并确保正式`run.jsonl`尚不存在。通过后才允许进入measured。
4. 补单元测试覆盖有效40条通过、错误/降级/空排名/重复组合/伪Rerank分别失败，以及`evaluate`门禁失败时manifest为failed且正式文件不存在；运行benchmark全量测试、重建CLI、记录新JAR SHA并中文提交后，再制定第七次独立复评分计划。

###### Benchmark 内建预热门禁实现与验收结果

- 新增`RagBenchmarkWarmupGate`，在`run`和`evaluate`两条路径的`runWarmup`之后、`executeMeasured`之前统一执行；warmup=0显式跳过，保留原有无预热调用语义。门禁严格校验期望记录数、唯一variant/query组合、四组均衡计数、合法variant、错误、降级、空排名，以及Rerank组正候选数和正`rerankMs`。
- 门禁失败抛稳定错误码`RAG_BENCHMARK_WARMUP_GATE_FAILED`，异常消息只包含expected/actual/unique/balanced和各失败计数，不包含query文本、JWT或凭据；两种Runner manifest失败路径都会写入该errorCode后原样抛出，禁止继续评分。
- 新增5个门禁测试，覆盖10×4健康样本、错误+降级+空排名、重复组合、缺失变体、以及“排名非空但Rerank候选/耗时为0”的fallback；原Runner warmup=0测试继续通过。新增`evaluate`真实本地HTTP集成测试，4条降级预热后断言异常、manifest=`failed`/errorCode正确、`warmup.jsonl=4`且正式`run.jsonl`不存在，直接证明没有外部轮询竞态。
- benchmark全量20/20测试通过，0 failure/error/skipped，BUILD SUCCESS；随后跳过重复测试重建fat CLI成功，新JAR SHA-256为`d797138759c5656d647abbc3a024f0a128fefd9becc52c7cdf1b69aeafa90c06`。拟提交Java/测试/计划文件的`git diff --check`通过。

###### SciFact 第七次独立复评分计划（执行前）

1. 使用内建预热门禁提交`ea5631a`、CLI JAR SHA-256 `d797138759c5656d647abbc3a024f0a128fefd9becc52c7cdf1b69aeafa90c06`、App JAR SHA-256 `c3bcd08910a52f0653663bfd89a14ac3275d08fbf8ab37f4924b9da7dec30af9`，以及全新空目录`/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-ea5631a-r7`。App JAR的Java功能仍对应此前已测试提交，脚本/门禁提交不能冒充其构建revision。
2. 固定prepared四文件原hash与targets SHA-256 `70273080edaa9507a03c4cb9e14099505034349c4cdcc07e7cc12e1eccfe5ffd`；复用同一隔离tenant、一个知识库、四个既有target，不重新摄取、不修改Qdrant索引、模型revision、Profile或候选数。
3. 对本地13307副本中的原benchmark用户按username/userId/tenantId/password/active/未删除条件更新唯一密码行；随机明文只存在受限临时文件和评测子进程环境，结束即清理，不写manifest、计划、shell参数或Git。更新行数不等于1即停止。
4. 固定seed=20260719、10 query×4 variant的40条warmup、请求上限120秒、load关闭。CLI内部门禁要求40记录、40唯一组合、四组各10、0错误、0降级、0空排名，并要求10条Rerank均有正候选数/正`rerankMs`；失败时manifest落稳定错误码且正式`run.jsonl`必须不存在。
5. 只有内部门禁通过才进入300 query×4 variant的1200条measured。运行期间周期核对记录数、唯一组合、错误、降级、空排名、8092/本地MySQL存活；出现任一无效样本立即SIGINT并保留目录，不覆盖、不续写、不拼接。
6. 只有1200条严格完整后才接受CLI原始metrics，并以独立`score`从prepared qrels和run重新计算，再核对两个报告hash、Recall@10/MRR@10/nDCG@10/MAP@10、分阶段延迟与四组件消融差异；任何缺失或不一致都不得称为成功run。

###### SciFact 第七次预热门禁执行结果

- 预执行核验HEAD为`ea5631a`，CLI fat JAR SHA-256为`d797138759c5656d647abbc3a024f0a128fefd9becc52c7cdf1b69aeafa90c06`，App JAR SHA-256为`c3bcd08910a52f0653663bfd89a14ac3275d08fbf8ab37f4924b9da7dec30af9`，targets SHA-256为`70273080edaa9507a03c4cb9e14099505034349c4cdcc07e7cc12e1eccfe5ffd`；prepared manifest/queries/qrels/document-map原hash未变。8092应用PID 27659明确连接本地13307，Rerank实际参数为3候选子批、20秒单批、60秒总deadline、最多2次重试，启动前无其他benchmark evaluator且输出目录不存在。
- 本地副本中隔离用户的唯一active password行按username/userId/tenantId同时匹配更新，影响严格为1；随机密码只进入受限临时文件和前台评测子进程环境，不在输出、manifest、计划或Git中出现。
- 新目录`/tmp/rag-quality-scifact-20260719/run-scifact-quality-eval-ea5631a-r7`写满40条warmup后，CLI内部门禁通过：40个唯一variant/query组合，Dense/Sparse/Hybrid-RRF/Hybrid-RRF+Rerank各10，0 error、0 degraded、0空`rankedDocumentIds`、0非法Rerank；10条Rerank均有正候选数和正`rerankMs`。
- 门禁期间持续核对正式`run.jsonl`不存在；第40条验证通过后CLI才创建正式文件并开始measured，首次观察为3条，manifest仍为running。当前只证明预热门禁闭环，不代表1200条完成；正式阶段继续逐条监控，禁止将该状态作为最终质量结果。

###### SciFact 第七次正式阶段外层超时结果与第八次修复计划（执行前）

- 第七次正式阶段在553条后由benchmark Java HTTP客户端抛`HttpTimeoutException: request timed out`并退出；manifest为failed/errorType=`HttpTimeoutException`，正式553条均唯一、0 error、0 degraded、0 empty，最后已落盘记录为Hybrid-RRF成功。该目录原样保留，不评分、不续写、不与后续run拼接。
- 按确定性的轮转顺序，下一条是同query的Hybrid-RRF+Rerank。CLI在`2026-07-19T14:42:57Z`达到120秒外层请求上限；后端并未失败，而是在约12秒后写入独立审计记录：status=success、10最终引用、无降级、总耗时131803ms，其中Embedding=65637ms、Dense=11159ms、Sparse=408ms、Rerank=54586ms、assemble=5ms。远端模型网关/TEI同时记录Rerank子批200，容器healthy、无OOM；本地MySQL当前真实查询正常。日志中21:52的Connection refused来自此前故意`kickstart -k`重启本地MySQL的恢复测试，不是本次14:42Z失败根因。
- 根因是benchmark外层120秒小于一次Hybrid+Rerank可能累加的合法组件预算与传输长尾：Embedding总deadline 30秒、Qdrant总deadline 30秒、Rerank总deadline 60秒，加配置/数据库/响应开销后理论边界已不低于120秒；本次后端完整成功的131.8秒是直接反例。不能把该成功响应误记为检索质量失败，也不能用已写数据库审计行手工补入run。
- 代码修复一：`run`/`evaluate` manifest对`HttpTimeoutException`写稳定`RAG_BENCHMARK_REQUEST_TIMEOUT`，其他未分类`IOException`写`RAG_BENCHMARK_IO`；仍原样抛出并终止整轮，不能把传输异常变成空排名后继续评分。补本地慢HTTP集成测试，断言failed manifest、稳定错误码且正式文件不存在。
- 脚本修复二：`run-local-rag-mini.sh`增加正整数`RAG_BENCHMARK_REQUEST_TIMEOUT_SECONDS`，默认保留120；第八次显式使用240秒，给120秒后端预算留100%外层余量。只改变评测客户端等待完整响应的上限，不修改App、Profile、TopK、模型、索引、并发或任何结果排序。
- 修复测试、重建CLI、中文提交后，以新提交/JAR SHA/全新空目录启动第八次40+1200；第七次553条只作为公网长尾与评测器边界证据，不进入任何指标聚合。

###### 评测外层超时分类与配置执行结果

- `RagBenchmarkRunner.run`与`evaluate`现共用同一错误码归类方法，优先保留业务API、协议和预热门禁错误；`HttpTimeoutException`稳定写入`RAG_BENCHMARK_REQUEST_TIMEOUT`，其他未分类`IOException`写入`RAG_BENCHMARK_IO`。异常仍原样向上抛出并把整轮manifest标记failed，没有把传输失败伪造成空召回或可继续评分的样本。
- `run-local-rag-mini.sh`新增`RAG_BENCHMARK_REQUEST_TIMEOUT_SECONDS`，默认值仍为120，run/evaluate与可选load阶段统一使用；仅接受1～3600的十进制正整数，非法值在登录和创建输出前以退出码2失败。第八次将显式使用240，不改变应用侧组件deadline。
- 新增本地慢HTTP集成回归：服务端接收请求后由Latch阻塞而不发送响应，100ms客户端超时后断言原始`HttpTimeoutException`、manifest=`failed`、errorType=`HttpTimeoutException`、稳定errorCode，且正式`run.jsonl`不存在；测试结束显式释放Latch并关闭服务端，不依赖固定长sleep。
- benchmark全量测试两次均为21/21通过、0 failure/error/skipped；`mvn package`成功生成fat CLI。脚本`bash -n`通过，非法超时0的真实脚本检查得到退出码2和预期错误摘要；拟提交文件`git diff --check`通过。新CLI JAR SHA-256为`b2b0d499843c3fce1da68f2577a5a1bb3d2af68e21c7c477ab20cb2db158d0ea`。
