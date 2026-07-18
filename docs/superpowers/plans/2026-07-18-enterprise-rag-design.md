# 企业级多租户 RAG 闭环设计计划

## 设计目标

在现有 Spring Boot 模块化单体、Agent/Workflow、会话上下文、租户权限、MinIO、MySQL、Kafka 与独立 RAG 服务器基础上，设计一套租户管理员可维护、Agent 可安全召回、结果可引用可评测可回滚的企业专属 RAG 能力。

## 当前阶段边界

- 本阶段只做架构、数据、接口、检索链路、部署和分阶段实施设计，不修改业务代码、数据库或服务器。
- 首期文件格式限定为 DOCX、PDF、Markdown；旧二进制 DOC 不默认承诺，除非后续明确引入 LibreOffice/Tika 转换沙箱。
- 优先采用可通过索引、算法、缓存、过滤、融合和评测获得收益的方法；不以大 GPU 集群或频繁大模型调用换取效果。
- 严格保持租户隔离、管理员写权限、普通成员只读召回、文档版本可追踪、删除可证明、回答引用可验证。

## 执行计划

1. 读取 `codex.md`、现有 Knowledge Base 页面、Asset/Session/Context/Tool/Agent 链路和部署资料，找出可复用边界与必须新增的端口。
2. 仅使用官方文档/论文核对候选技术：Qdrant/pgvector/Elasticsearch 类检索引擎、解析器、Embedding、Sparse Retrieval、Reranker、融合与评测方案。
3. 设计控制面、数据面和查询面：上传→校验→解析→标准化→切分→向量化→索引→发布，以及问题理解→权限过滤→多路召回→融合→重排→上下文构造→引用校验→反馈。
4. 设计多租户数据模型、状态机、幂等/版本/删除语义、配额、安全、观测、灾备和离线评测闭环。
5. 输出完整设计文档，给出技术选型、依赖图、分阶段交付、验收指标、风险与明确不采用项；安排一次对抗性架构复核。

## 执行记录

### 2026-07-18：设计闭环完成

- 阅读 `codex.md` 及现有 RAG 占位页、三张 RAG 表/DAO、附件解析、MinIO、租户角色、Agent/Workflow、Context Manager、Kafka 和 Trace/AiLog 代码，确认可复用接口与现存缺口。
- 对 `RAG-Server` 做只读资源审计：CentOS 7、16 核、15 GiB RAM、40 GiB XFS、无 Docker；未修改服务器、未上传项目。
- 使用 Docling、Qdrant、BGE-M3/BGE Reranker、Hugging Face TEI、RAGAS、OpenTelemetry、pgvector、CentOS 官方资料或原始论文核对技术边界。
- 新增 `docs/architecture/2026-07-18-enterprise-rag-architecture.md`，完成上传安全、异步摄取、结构解析、层级切分、混合召回、重排、引用、Context 集成、多租户、数据模型、前端、评测、观测、灾备和八阶段交付设计。
- 按 Blueprint 安排独立 Agent 做对抗性架构复核，并吸收关键意见：Java 独占业务库、Worker 内部作业 API、lease fencing、不可变 index generation、管理员实时权限、全表 tenant 边界、统一授权查询构造器、required fail-close、流式上传、租户内去重、可消融检索开关和单机资源降档。
- 本阶段只产生设计文档与计划记录，没有修改业务代码、数据库或服务器配置。
