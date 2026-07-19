# RAG 模块文档入口

## 当前实现结论

RAG 业务控制面、摄取 Worker、检索编排、评测客户端和 Agent 上下文接入均位于本地 Java 项目中。RAG 专用服务器只承载 Qdrant、Docling、Embedding、Reranker、模型鉴权网关和监控环境；本地 Java/Vue 源码、JAR 和业务镜像不上传该服务器。

```text
租户管理员 Web
  -> Spring Boot API（可信 JWT / TenantContextHolder）
  -> MySQL 元数据与任务真相 + MinIO 原件 + Kafka 唤醒
  -> Java RagIngestWorker
  -> Docling -> Chunker -> Embedding -> Qdrant

Agent / Workflow
  -> ContextInjectionPlugin（每次模型调用前）
  -> RagContextContributor
  -> Dense / Sparse -> RRF -> 可选 Rerank
  -> Token 预算上下文 + citation_id
```

关键边界：

- MySQL 是业务事实源，Qdrant 是按版本和 generation 可重建的索引。
- tenantId、userId 和 roleCode 只来自服务端可信上下文，浏览器不能指定租户。
- 浏览器不直连 Qdrant、Docling、Embedding 或 Reranker。
- 文档内容按不可信参考资料处理，不能取得系统提示词或工具调用权限。
- 必需知识库不可用时 fail closed；可选知识库或 Reranker 故障必须显式标记降级。
- 当前 Qdrant 按联调要求公网无认证开放，不能存放生产或敏感数据；上线前必须恢复认证并限制来源。

## 文档导航

- [管理与调试 API](api.md)
- [运行、配置和故障恢复](operations.md)
- [公开数据集、质量消融与性能评测](evaluation.md)
- [目标计划与逐次真实执行记录](../superpowers/plans/2026-07-18-rag-java-module-and-evaluation-target-plan.md)
- [最初架构设计](../architecture/2026-07-18-enterprise-rag-architecture.md)
- 服务器、中间件版本、端口和受控凭据位置见项目根目录 `codex.md`；本文档不复制任何密码、API Key 或 Bearer。

## 当前验证状态

截至 2026-07-19：

- SciFact 5183 篇文档已通过与业务接口相同的本地隔离链路生成 7548 个可检索子块；这里的“业务链路”指真实 Java API/Worker/远端中间件代码路径，不代表生产环境或生产数据。
- Markdown 已完成该隔离链路的上传、摄取、检索和取消联调；PDF/DOCX 已完成安全校验、客户端协议测试和 Docling 直连探测，但三格式统一业务链路 E2E 仍待验收。
- Dense、Sparse、Hybrid+RRF、Hybrid+RRF+Rerank 的完整 300 查询×4方案、共1200次正式评分仍在运行；完成前不发布部分质量指标。
- Agent 调用前 RAG 注入及引用格式已有代码和单元测试；真实 Agent 回答携带 citation 的流式/非流式 E2E 尚待完成。
- mini 数据集并发 pilot 和批量装载优化对照已完成；完整摄取性能、SciFact 并发复测和最终容量建议尚待完成。

因此，本目录描述的是已经实现的运行契约和可复现方法，不代表阶段 8 上线审计已经完成。
