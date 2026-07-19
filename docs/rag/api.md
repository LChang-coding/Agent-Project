# RAG 管理与调试 API

## 通用约束

- API 基址沿用应用现有 `/api` 前缀；下表路径从 `/api/v1/rag` 开始。
- 所有请求必须携带现有登录 Bearer。租户、用户和角色由 `TenantContextHolder` 注入，不接受请求体覆盖。
- 管理操作由领域层校验 owner/admin；普通成员不能靠隐藏前端按钮绕过后端鉴权。
- 业务成功码沿用项目统一 `0000`；HTTP 200 不等于业务成功，调用方必须同时检查响应 `code`。
- 更新 Profile、删除 Binding 和首次受理文档删除使用 revision 乐观锁，缺少或过期 revision 必须刷新后重试。文档已有同一幂等删除任务时，重复 DELETE 仅查询当前任务或从失败 checkpoint 继续，此时忽略旧 revision，但仍严格校验租户、知识库、文档和任务范围。

## 端点清单

| 方法 | 路径 | 用途 | 关键输入/输出 |
|---|---|---|---|
| POST | `/api/v1/rag/knowledge-bases` | 创建当前租户知识库 | `name`、`description`；返回服务端生成 ID、状态和 revision |
| GET | `/api/v1/rag/knowledge-bases` | 查询当前租户知识库 | 不接受 tenantId |
| POST | `/api/v1/rag/knowledge-bases/{knowledgeBaseId}/documents` | 上传文档 | `multipart/form-data` 的 `file`；业务策略支持 PDF、DOCX、MD/Markdown且上限50 MiB；Servlet限制、Nacos下发覆盖和反向代理请求体限制必须分别对齐 |
| GET | `/api/v1/rag/knowledge-bases/{knowledgeBaseId}/documents` | 查询知识库文档 | 返回 active/target generation 和状态 |
| DELETE | `/api/v1/rag/knowledge-bases/{knowledgeBaseId}/documents/{documentId}?expectedRevision=N` | 异步删除文档 | 仅owner/admin；必须携带当前 revision；返回可跟踪的 `DELETE` 任务 |
| GET | `/api/v1/rag/knowledge-bases/{knowledgeBaseId}/ingest-tasks?limit=100` | 查询最新摄取任务 | 仅owner/admin；`limit` 1–200，按最新在前返回，不暴露lease、fencing、checkpoint或内部错误消息 |
| GET | `/api/v1/rag/ingest-tasks/{taskId}` | 查询摄取任务 | 返回 operation、stage、状态、chunk 进度、attempt 和稳定错误码 |
| POST | `/api/v1/rag/ingest-tasks/{taskId}/cancel` | 请求取消 | 可选 `reason`；未领取任务同步关闭，持有租约的任务进入取消屏障 |
| POST | `/api/v1/rag/retrieval-profiles` | 创建检索策略 | 模式、融合、候选数、Rerank、邻接窗口、Token 预算等 |
| PUT | `/api/v1/rag/retrieval-profiles/{profileId}` | 更新检索策略 | 必须包含 `expectedRevision` |
| GET | `/api/v1/rag/retrieval-profiles` | 查询检索策略 | 当前租户范围 |
| POST | `/api/v1/rag/bindings` | 绑定 Agent/Workflow 与知识库 | `targetType`、`targetId`、KB、Profile、required、maxTokens、priority |
| GET | `/api/v1/rag/bindings` | 查询绑定 | 当前租户范围 |
| DELETE | `/api/v1/rag/bindings/{bindingId}?expectedRevision=N` | 删除绑定 | 必须携带当前 revision |
| POST | `/api/v1/rag/retrieval-debug` | 管理员调试真实检索链 | 目标、问题和可选 Token 预算；返回候选量、分段耗时、降级原因和引用 |

## 上传安全边界

后端先把 Multipart 流转存到受控临时文件，再执行：

- 文件名、扩展名、MIME、实际长度与声明长度校验。
- PDF magic bytes 校验。
- DOCX OOXML 必需条目、ZIP 路径穿越、重复条目和解压膨胀限制。
- Markdown 严格 UTF-8、空文件及 NUL 字符校验。
- MinIO 服务端对象路径和流式 SHA-256；失败清理半成品。
- 同租户、同知识库、相同内容哈希幂等复用，不泄漏其他租户是否存在同一文件。

## 文档删除语义

- 受理删除时，文档聚合根行加锁，并在同一 MySQL 事务内将文档及其全部版本置为 `DELETING`、创建唯一删除任务和 Outbox 事件。
- 存在摄取、重建或其他活动任务时拒绝删除；并发请求不能越过 `expectedRevision` CAS。
- Worker 按“Qdrant全版本向量 -> MySQL chunks -> MinIO原文件与解析产物”执行幂等清理，每个外部副作用前后都校验 lease/fencing。
- 删除任务不支持取消；删除失败或重试耗尽时保持 `DELETING`，不会恢复为可检索状态。重复删除返回原任务，失败任务则从原 checkpoint 重入队。
- 合法删除窗口中遇到的残留向量命中会被丢弃；租户、知识库、版本或 generation 不一致仍会 fail closed。
- 文档列表保留 `DELETED` 审计墓碑，但墓碑不可检索、不包含活动版本或 generation；“删除”指向量、分块、原文件和解析产物的物理清理，不是抹除任务审计事实。

## 调试响应语义

`retrieval-debug` 返回的核心字段包括：

- `retrievalId`、`estimatedTokenCount`、`degraded`、`degradationReasons`。
- 候选数：Dense、Sparse、Fusion、Rerank。
- 阶段耗时：configuration、embedding、dense、sparse、fusion、rerank、hydration、assembly、audit、pipeline total 和完整 service。
- 引用：citation/document/version/generation/chunk、页码、标题路径、上下文及各阶段分数。

`totalMs` 是检索管线耗时，不包含随后同步审计；`serviceMs` 计到审计尝试结束。客户端墙钟与 `serviceMs` 的差值还包含 Controller/JSON、本机网络和客户端排队，不能直接称为服务队列耗时。

## Agent/Workflow 语义

Binding 建立后，`ContextInjectionPlugin` 在每次模型调用前组装上下文，并由 `RagContextContributor` 执行检索。注入的每段资料包含 `citation_id`、文档名、版本、页码和标题，并显式标记为 `untrusted_reference`。资料内出现的命令、角色要求、提示词或工具调用要求均不具备指令权限。

当前代码会要求模型在事实回答中使用对应 `citation_id`；最终上线验收还必须通过真实流式与非流式回答证明引用从检索审计一直贯穿到用户可见答案。
