# 会话级上下文管理器实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为单会话提供可恢复、预算可控、可异步压缩的上下文管理能力，并在所有 ADK 模型调用前统一注入。

**Architecture:** MySQL 保存原始消息、版本化长期摘要和压缩任务；Redis 是可重建缓存；Kafka 负责阈值触发的异步压缩。ADK 插件组装并注入摘要、最近消息和未来 RAG 片段，业务会话与临时 ADK 执行会话分离。

**Tech Stack:** Spring Boot、Google ADK、Spring AI、MyBatis、MySQL 8、Redis、Kafka KRaft、Spring Kafka。

## 全局约束

- 遵循 `codex.md`：Domain 不依赖 MyBatis、Spring MVC、Kafka 或 Redis 的具体实现。
- 全部租户查询携带 tenantId、userId、sessionId；消费者必须清理 TenantContextHolder 和 MDC。
- 不提交密码、TLS 私钥、Kafka 配置中的密钥或运行日志。
- RAG 仅提供 ContextContributor 扩展点，不实现召回。

---

### Task 1: 部署安全的单节点 Kafka

- 在 `CentOS-Server` 使用独立 Docker Compose 部署 KRaft Kafka。
- 配置 SASL_SSL、应用和运维两个 SCRAM 身份、持久化卷、健康检查、资源上限、三类 context topic。
- 从本地使用应用身份验证 TLS、认证、生产、消费和容器重启恢复。

### Task 2: 上下文持久化模型与仓储

- 新增非破坏性 MySQL 增量脚本：消息 token 估算字段、摘要快照表、压缩任务表和索引。
- 在 Domain 定义上下文实体、仓储接口、压缩端口、贡献者接口和 token 计数接口。
- 在 Infrastructure 添加 MyBatis PO、DAO、Mapper 和 Repository；原始消息仍是唯一事实来源。

### Task 3: 预算组装与长期记忆压缩

- 实现 TokenCounter、ContextAssembler、ConversationMemoryService 与 no-op RAG contributor。
- 按“摘要、最近消息、上游输出、RAG”的优先级与预算组装；硬阈值时同步压缩或降级裁剪。
- 用结构化 JSON 生成版本化摘要，使用版本和覆盖序号 CAS 激活，避免重复消费覆盖新摘要。

### Task 4: Kafka 异步压缩闭环

- 助手消息提交后，创建幂等压缩任务并立即发布 `taskId`；同会话再次激活时只重投自身未完成任务。
- 消费者原子领取任务，成功后更新摘要、任务和缓存；失败使用 retry topic/DLT，重复消息安全。

### Task 5: ADK 注入与聊天链路整合

- 为 Runner 自动装配 ContextInjectionPlugin；插件在 beforeModelCallback 读取业务会话 state 并注入上下文。
- 将 ChatService 普通、流式、多模态和 DAG 调用改为临时 ADK session，业务 sessionId 继续供权限、存储和工具上下文使用。
- 工作流固定历史 cutoff，所有节点共享同一历史切面并保留节点上游输出。

### Task 6: 配置、观测与验证

- 在 Nacos/本地配置中新增 feature flag、模型窗口和预算策略；默认关闭，缺少模型窗口时拒绝启用。
- 记录上下文 token、摘要版本、缓存命中、裁剪原因、任务状态和错误，不记录完整内容。
- 添加单元/集成测试，执行相关 Maven 测试和多模块编译；部署后验证 Kafka TLS、认证、topic、重试、容器恢复。
